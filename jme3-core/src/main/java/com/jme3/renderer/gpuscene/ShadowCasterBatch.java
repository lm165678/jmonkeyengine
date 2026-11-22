package com.jme3.renderer.gpuscene;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.MatParamOverride;
import com.jme3.math.Matrix4f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.instancing.InstancedGeometry;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal draw packets for the managed shadow camera's already culled list.
 * Shares one mesh holding only positions and UVs across lights and cascades
 * of a viewport. Alpha-tested casters share draws by texture and cutoff.
 * @see com.jme3.shadow.ShadowManager#setIndirectDrawingEnabled(boolean)
 */
public final class ShadowCasterBatch implements AutoCloseable {
    private static final String LIGHTING = "Common/MatDefs/Light/Lighting.j3md";
    private static final String PBR = "Common/MatDefs/Light/PBRLighting.j3md";
    private static final String UNSHADED = "Common/MatDefs/Misc/Unshaded.j3md";
    private final Map<Mesh, Source> sources = new IdentityHashMap<>();
    private final Map<Material, Group> materials = new IdentityHashMap<>();
    private final List<Group> groups = new ArrayList<>();
    private final Mesh packed = new Mesh();
    private boolean stale;

    private static final class Source {
        final VertexBuffer positions;
        final long positionRevision;
        final int baseVertex;
        final VertexBuffer uv;
        final long uvRevision;
        final VertexBuffer[] indices;
        final long[] indexRevisions;
        final int[] firstIndex;
        final int[] indexCount;

        Source(Mesh mesh, int baseVertex) {
            positions = mesh.getBuffer(Type.Position);
            positionRevision = positions.getDataRevision();
            this.baseVertex = baseVertex;
            uv = packableAttribute(mesh, Type.TexCoord, 2) ? mesh.getBuffer(Type.TexCoord) : null;
            uvRevision = uv == null ? 0 : uv.getDataRevision();
            int levels = Math.max(1, mesh.getNumLodLevels());
            indices = new VertexBuffer[levels];
            indexRevisions = new long[levels];
            firstIndex = new int[levels];
            indexCount = new int[levels];
        }
    }

    public ShadowCasterBatch(AssetManager assets, List<Geometry> casters) {
        int vertices = 0;
        int indices = 0;
        for (Geometry geometry : casters) {
            Mesh mesh = geometry.getMesh();
            if (!eligible(geometry) || !packable(mesh)) {
                continue;
            }
            Material material = geometry.getMaterial();
            Float cutoff = material.getParamValue("AlphaDiscardThreshold");
            Texture2D alpha = alphaTexture(material);
            if (cutoff != null && (alpha == null || !packableAttribute(mesh, Type.TexCoord, 2))) {
                continue;
            }
            materials.computeIfAbsent(material, key -> group(assets, cutoff, alpha));
            if (sources.containsKey(mesh)) {
                continue;
            }
            Source source = new Source(mesh, vertices);
            sources.put(mesh, source);
            vertices = Math.addExact(vertices, mesh.getVertexCount());
            for (int lod = 0; lod < source.indices.length; lod++) {
                VertexBuffer buffer = indices(mesh, lod);
                source.indices[lod] = buffer;
                source.indexRevisions[lod] = buffer.getDataRevision();
                source.firstIndex[lod] = indices;
                source.indexCount[lod] = buffer.getData().limit();
                indices = Math.addExact(indices, source.indexCount[lod]);
            }
        }
        if (sources.isEmpty()) {
            return;
        }
        FloatBuffer positionData = BufferUtils.createFloatBuffer(Math.multiplyExact(vertices, 3));
        FloatBuffer uvData = BufferUtils.createFloatBuffer(Math.multiplyExact(vertices, 2));
        IntBuffer indexData = BufferUtils.createIntBuffer(indices);
        sources.forEach((mesh, source) -> {
            positionData.position(source.baseVertex * 3);
            positionData.put((FloatBuffer) source.positions.getDataReadOnly());
            if (source.uv != null) {
                uvData.position(source.baseVertex * 2);
                uvData.put((FloatBuffer) source.uv.getDataReadOnly());
            }
            for (int lod = 0; lod < source.indices.length; lod++) {
                indexData.position(source.firstIndex[lod]);
                IndexBuffer input = IndexBuffer.wrapIndexBuffer(source.indices[lod].getData());
                for (int i = 0; i < source.indexCount[lod]; i++) {
                    indexData.put(input.get(i));
                }
            }
        });
        positionData.clear();
        uvData.clear();
        indexData.clear();
        packed.setBuffer(Type.Position, 3, positionData);
        packed.setBuffer(Type.TexCoord, 2, uvData);
        packed.setBuffer(Type.Index, 3, indexData);
    }

    private Group group(AssetManager assets, Float cutoff, Texture2D alpha) {
        for (Group group : groups) {
            if (group.matches(cutoff, alpha)) {
                return group;
            }
        }
        Group group = new Group(assets, cutoff, alpha);
        groups.add(group);
        return group;
    }

    private static Texture2D alphaTexture(Material material) {
        if (material.getParam("AlphaDiscardThreshold") == null) {
            return null;
        }
        String definition = material.getMaterialDef().getAssetName();
        String name = PBR.equals(definition) ? "BaseColorMap" : LIGHTING.equals(definition) ? "DiffuseMap" : "ColorMap";
        Texture texture = material.getParamValue(name);
        return texture instanceof Texture2D ? (Texture2D) texture : null;
    }

    private static boolean eligible(Geometry geometry) {
        Mesh mesh = geometry.getMesh();
        Material material = geometry.getMaterial();
        if (mesh == null || material == null || geometry.isGrouped()
                || geometry instanceof InstancedGeometry || mesh.getInstanceCount() != 1
                || mesh.isAnimated() || mesh.getMorphTargets().length > 0
                || material.getParam("NumberOfBones") != null
                || material.getParam("NumberOfMorphTargets") != null
                || Boolean.TRUE.equals(material.getParamValue("UseInstancing"))
                || hasShadowOverrides(geometry.getWorldMatParamOverrides())) {
            return false;
        }
        String definition = material.getMaterialDef().getAssetName();
        return LIGHTING.equals(definition) || PBR.equals(definition) || UNSHADED.equals(definition);
    }

    public static boolean hasShadowOverrides(Iterable<MatParamOverride> overrides) {
        for (MatParamOverride override : overrides) {
            if (!override.isEnabled()) {
                continue;
            }
            switch (override.getName()) {
                case "AlphaDiscardThreshold":
                case "NumberOfBones":
                case "NumberOfMorphTargets":
                case "UseInstancing":
                case "BaseColorMap":
                case "DiffuseMap":
                case "ColorMap":
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    private static boolean packable(Mesh mesh) {
        if (mesh.getMode() != Mesh.Mode.Triangles || mesh.getVertexCount() == 0
                || !packableAttribute(mesh, Type.Position, 3)) {
            return false;
        }
        for (int lod = 0; lod < Math.max(1, mesh.getNumLodLevels()); lod++) {
            VertexBuffer buffer = indices(mesh, lod);
            if (buffer == null || !packableUsage(buffer)
                    || buffer.getData() == null || buffer.getData().limit() == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean packableAttribute(Mesh mesh, Type type, int components) {
        VertexBuffer buffer = mesh.getBuffer(type);
        return buffer != null && packableUsage(buffer) && buffer.getFormat() == VertexBuffer.Format.Float
                && buffer.getData() instanceof FloatBuffer && buffer.getNumComponents() == components
                && buffer.getStride() == 0 && buffer.getOffset() == 0 && !buffer.isInstanced()
                && buffer.getData().limit() == mesh.getVertexCount() * components;
    }

    private static boolean packableUsage(VertexBuffer buffer) {
        return buffer.getUsage() == VertexBuffer.Usage.Static || buffer.getUsage() == VertexBuffer.Usage.Dynamic;
    }

    private static VertexBuffer indices(Mesh mesh, int lod) {
        return mesh.getNumLodLevels() == 0 ? mesh.getBuffer(Type.Index) : mesh.getLodLevel(lod);
    }

    public boolean isStale() {
        return stale;
    }

    /**
     * Draws compatible entries and leaves the rest in the queue, in their
     * original order, for the shadow renderer's usual PreShadow technique.
     * The caller owns culling and has already bound the shadow camera and state.
     *
     * @return number of casters sent through indexed indirect drawing
     */
    public int draw(RenderManager manager, GeometryList casters, GeometryList fallback) {
        if (groups.isEmpty() || casters.size() < 2) {
            return 0;
        }
        for (Group group : groups) {
            group.count = 0;
        }
        int count = 0;
        for (int i = 0; i < casters.size(); i++) {
            Geometry geometry = casters.get(i);
            Mesh mesh = geometry.getMesh();
            Source source = sources.get(mesh);
            int lod = geometry.getLodLevel();
            if (source == null || mesh.getMode() != Mesh.Mode.Triangles || !eligible(geometry)) {
                fallback.add(geometry);
                continue;
            }
            Material material = geometry.getMaterial();
            Group group = materials.get(material);
            Float cutoff = material.getParamValue("AlphaDiscardThreshold");
            Texture2D alpha = alphaTexture(material);
            if (group == null || !group.matches(cutoff, alpha)) {
                stale |= cutoff == null || (alpha != null && packableAttribute(mesh, Type.TexCoord, 2));
                fallback.add(geometry);
                continue;
            }
            if (cutoff != null && (source.uv == null || source.uv != mesh.getBuffer(Type.TexCoord)
                    || source.uvRevision != source.uv.getDataRevision())) {
                stale |= packableAttribute(mesh, Type.TexCoord, 2);
                fallback.add(geometry);
                continue;
            }
            if (lod >= source.indices.length || source.positions != mesh.getBuffer(Type.Position)
                    || source.positionRevision != source.positions.getDataRevision()
                    || source.indices[lod] != indices(mesh, lod)
                    || source.indexRevisions[lod] != source.indices[lod].getDataRevision()) {
                stale |= packable(mesh);
                fallback.add(geometry);
                continue;
            }
            group.append(source, lod, geometry);
            count++;
            geometry.queueDistance = Float.NEGATIVE_INFINITY;
        }
        for (Group group : groups) {
            if (group.count != 0) {
                group.draw(manager);
            }
        }
        return count;
    }

    @Override
    public void close() {
        if (!sources.isEmpty()) {
            packed.getBuffer(Type.Position).dispose();
            packed.getBuffer(Type.TexCoord).dispose();
            packed.getBuffer(Type.Index).dispose();
        }
        for (Group group : groups) {
            group.commands.dispose();
            group.transforms.dispose();
        }
    }

    private final class Group {
        final Float cutoff;
        final Texture2D alpha;
        final BufferObject commands = new BufferObject();
        final BufferObject transforms = new BufferObject();
        final GpuSceneGeometry packet;
        int count;
        int capacity;
        IntBuffer commandData;
        FloatBuffer transformData;
        int[] sourceVertexCounts;

        Group(AssetManager assets, Float cutoff, Texture2D alpha) {
            this.cutoff = cutoff;
            this.alpha = alpha;
            commands.setAccessHint(BufferObject.AccessHint.Stream);
            transforms.setAccessHint(BufferObject.AccessHint.Stream);
            Geometry source = new Geometry("Managed shadow", packed);
            Material material = new Material(assets, PBR);
            if (cutoff != null) {
                material.setFloat("AlphaDiscardThreshold", cutoff);
                material.setTexture("BaseColorMap", alpha);
            }
            source.setMaterial(material);
            packet = new GpuSceneGeometry(source, commands, transforms, 0, 0);
        }

        boolean matches(Float candidateCutoff, Texture2D candidateAlpha) {
            return Objects.equals(cutoff, candidateCutoff) && alpha == candidateAlpha;
        }

        void append(Source source, int lod, Geometry geometry) {
            if (count == capacity) {
                capacity = Math.max(16, capacity * 2);
                commandData = grow(commands, count * 20, capacity * 20).asIntBuffer();
                transformData = grow(transforms, count * 64, capacity * 64).asFloatBuffer();
                sourceVertexCounts = sourceVertexCounts == null ? new int[capacity]
                        : Arrays.copyOf(sourceVertexCounts, capacity);
            }
            commandData.position(count * 5);
            commandData.put(source.indexCount[lod]).put(1).put(source.firstIndex[lod])
                    .put(source.baseVertex).put(count);
            Matrix4f world = geometry.isIgnoreTransform() ? Matrix4f.IDENTITY : geometry.getWorldMatrix();
            world.fillFloatBuffer(transformData.position(count * 16), true);
            sourceVertexCounts[count] = geometry.getVertexCount();
            count++;
        }

        void draw(RenderManager manager) {
            commands.getData().position(0).limit(count * 20);
            transforms.getData().position(0).limit(count * 64);
            commands.setUpdateNeeded();
            transforms.setUpdateNeeded();
            Renderer renderer = manager.getRenderer();
            renderer.updateShaderStorageBufferObjectData(transforms);
            packet.enableMultiDraw(packed, count, renderer.getStatistics().isEnabled()
                    ? Arrays.copyOf(sourceVertexCounts, count) : null);
            manager.renderGeometry(packet);
        }
    }

    private static ByteBuffer grow(BufferObject target, int used, int size) {
        ByteBuffer data = BufferUtils.createByteBuffer(size);
        data.put(target.getData().limit(used)).clear();
        target.setData(data);
        BufferUtils.destroyDirectBuffer(data);
        return target.getData().clear();
    }
}
