package com.jme3.renderer.gpuscene;

import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferUtils;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Immutable registration-time draw layout. Source mesh buffers are never modified. */
final class MeshDrawPlan implements AutoCloseable {
    final List<Geometry> sources = new ArrayList<>();
    final List<Integer> lods = new ArrayList<>();
    final int[] drawData;
    final int[] instanceData;
    final int[] vertexCounts;
    final int visibleCapacity;
    final Mesh packed;
    private final List<VertexBuffer> owned = new ArrayList<>();

    static boolean compatible(Mesh a, Mesh b) {
        if (a == b) {
            return true;
        }
        if (a.getMode() != b.getMode() || a.getLineWidth() != b.getLineWidth()) {
            return false;
        }
        int attributes = 0;
        for (VertexBuffer buffer : a.getBufferList()) {
            if (buffer.getBufferType() == VertexBuffer.Type.Index || buffer.getUsage() == VertexBuffer.Usage.CpuOnly) {
                continue;
            }
            VertexBuffer other = b.getBuffer(buffer.getBufferType());
            if (!packable(buffer, a) || other == null || !packable(other, b)
                    || buffer.getFormat() != other.getFormat() || buffer.getNumComponents() != other.getNumComponents()
                    || buffer.isNormalized() != other.isNormalized()) {
                return false;
            }
            attributes++;
        }
        int otherAttributes = 0;
        for (VertexBuffer buffer : b.getBufferList()) {
            if (buffer.getBufferType() != VertexBuffer.Type.Index && buffer.getUsage() != VertexBuffer.Usage.CpuOnly) {
                otherAttributes++;
            }
        }
        return attributes == otherAttributes;
    }

    private static boolean packable(VertexBuffer buffer, Mesh mesh) {
        return buffer.getData() != null && buffer.getStride() == 0 && buffer.getOffset() == 0
                && buffer.getInstanceSpan() == 0 && buffer.getNumElements() == mesh.getVertexCount()
                && buffer.getFormat() != VertexBuffer.Format.Double
                && buffer.getBufferType() != VertexBuffer.Type.InterleavedData;
    }

    MeshDrawPlan(List<Geometry> instances, int maximumLods, boolean multiDraw) {
        List<Geometry> unique = new ArrayList<>();
        Map<Mesh, Integer> meshIndices = new IdentityHashMap<>();
        for (Geometry geometry : instances) {
            if (!meshIndices.containsKey(geometry.getMesh())) {
                meshIndices.put(geometry.getMesh(), unique.size());
                unique.add(geometry);
            }
        }
        if (!multiDraw && unique.size() != 1) {
            throw new IllegalArgumentException("Cross-mesh plans require multi-draw");
        }
        int[] instanceCounts = new int[unique.size()];
        for (Geometry geometry : instances) {
            instanceCounts[meshIndices.get(geometry.getMesh())]++;
        }
        int[] firstDraw = new int[unique.size()];
        int[] levelCounts = new int[unique.size()];
        int totalIndices = 0;
        for (int mesh = 0; mesh < unique.size(); mesh++) {
            Mesh source = unique.get(mesh).getMesh();
            firstDraw[mesh] = sources.size();
            levelCounts[mesh] = Math.min(maximumLods, Math.max(1, source.getNumLodLevels()));
            for (int level = 0; level < levelCounts[mesh]; level++) {
                sources.add(unique.get(mesh));
                lods.add(level);
                totalIndices = Math.addExact(totalIndices, indices(source, level).getData().limit());
            }
        }
        instanceData = new int[Math.multiplyExact(instances.size(), 2)];
        for (int i = 0; i < instances.size(); i++) {
            int mesh = meshIndices.get(instances.get(i).getMesh());
            instanceData[i * 2] = firstDraw[mesh];
            instanceData[i * 2 + 1] = levelCounts[mesh];
        }
        drawData = new int[Math.multiplyExact(sources.size(), 4)];
        vertexCounts = new int[sources.size()];
        IntBuffer packedIndices = multiDraw ? BufferUtils.createIntBuffer(totalIndices) : null;
        int baseVertex = 0;
        int baseInstance = 0;
        for (int mesh = 0; mesh < unique.size(); mesh++) {
            Mesh source = unique.get(mesh).getMesh();
            if (!compatible(unique.get(0).getMesh(), source)) {
                throw new IllegalArgumentException("Incompatible vertex formats in draw plan");
            }
            for (int level = 0; level < levelCounts[mesh]; level++) {
                int draw = firstDraw[mesh] + level;
                IndexBuffer input = IndexBuffer.wrapIndexBuffer(indices(source, level).getData());
                drawData[draw * 4] = input.size();
                drawData[draw * 4 + 1] = multiDraw ? packedIndices.position() : 0;
                drawData[draw * 4 + 2] = multiDraw ? baseVertex : 0;
                drawData[draw * 4 + 3] = baseInstance;
                vertexCounts[draw] = source.getVertexCount();
                baseInstance = Math.addExact(baseInstance, instanceCounts[mesh]);
                if (multiDraw) {
                    for (int i = 0; i < input.size(); i++) {
                        packedIndices.put(input.get(i));
                    }
                }
            }
            baseVertex = Math.addExact(baseVertex, source.getVertexCount());
        }
        visibleCapacity = baseInstance;
        if (!multiDraw) {
            packed = null;
            return;
        }
        if (unique.size() == 1) {
            packed = unique.get(0).getMesh().clone();
            packed.setLodLevels(null);
            packed.clearBuffer(VertexBuffer.Type.Index);
        } else {
            packed = new Mesh();
            packed.setMode(unique.get(0).getMesh().getMode());
            packed.setLineWidth(unique.get(0).getMesh().getLineWidth());
            for (VertexBuffer template : unique.get(0).getMesh().getBufferList()) {
                if (template.getBufferType() == VertexBuffer.Type.Index || template.getUsage() == VertexBuffer.Usage.CpuOnly) {
                    continue;
                }
                VertexBuffer target = new VertexBuffer(template.getBufferType());
                target.setupData(VertexBuffer.Usage.Static, template.getNumComponents(), template.getFormat(),
                        VertexBuffer.createBuffer(template.getFormat(), template.getNumComponents(), baseVertex));
                target.setNormalized(template.isNormalized());
                int offset = 0;
                for (Geometry geometry : unique) {
                    Mesh source = geometry.getMesh();
                    source.getBuffer(template.getBufferType()).copyElements(0, target, offset, source.getVertexCount());
                    offset += source.getVertexCount();
                }
                packed.setBuffer(target);
                owned.add(target);
            }
        }
        packedIndices.flip();
        packed.setBuffer(VertexBuffer.Type.Index, 3, packedIndices);
        owned.add(packed.getBuffer(VertexBuffer.Type.Index));
        packed.updateBound();
    }

    private static VertexBuffer indices(Mesh mesh, int lod) {
        return mesh.getNumLodLevels() == 0 ? mesh.getBuffer(VertexBuffer.Type.Index) : mesh.getLodLevel(lod);
    }

    @Override
    public void close() {
        for (VertexBuffer buffer : owned) {
            buffer.dispose();
        }
        owned.clear();
    }
}
