package com.jme3.scene.plugins.sdf;

import com.jme3.export.*;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Persistent SDF + surface capture layout with explicit source validation.
 * Contains no GPU objects or scene lighting. Runtime cache residency is independent.
 * External material/shader/texture CONTENT revisions must be supplied by the asset
 * pipeline; filenames and a j3o timestamp cannot detect external file changes.
 * Validation is an explicit SDK/load-time operation, never a per-frame hash.
 *
 * <pre>{@code
 * // 第一步：SDK 收集外部贴图、j3md、shader 依赖的内容版本（如内容哈希）。
 * String revision = assetDependencyContentHash;
 * // 第二步：用户点击“烘焙”；不可同时修改模型。离线模型可在工作线程生成。
 * SdfBakeData data = SdfBakeData.bake(model, settings, revision, progress);
 * // 第三步：成功后回场景线程挂载，再由 SDK 另存 j3o。失败/取消不会替换旧数据。
 * data.attachTo(model);
 * BinaryExporter.getInstance().save(model, new File("Model-with-sdf.j3o"));
 * // 若编辑的是外部 j3m：先保存对应 j3m，或者明确 material.setKey(null) 将编辑内嵌到 j3o。
 * // Geometry 加载时优先读取外部 materialName，未保存的 j3m 编辑不会因为保存 j3o 自动生效。
 * // 第四步：加载后显式验证。移动模型根节点、修改灯光不使局部空间烘焙失效。
 * SdfBakeData loaded = model.getUserData(SdfBakeData.USER_DATA_KEY);
 * SdfBakeData.Status status = loaded.validate(model, currentDependencyContentHash);
 * // VALID：直接复用；MATERIAL_CHANGED：SDF/布局仍可复用，只重捕获属性。
 * // GEOMETRY_CHANGED / INVALIDATED：重新烘焙，不能继续用旧映射。
 * // 第五步：SDK 的“强制重新烘焙”可先 invalidate()，再重复第二、三步。
 * // 材质已改且几何未改：loaded.refreshMaterials(model, revision) 更新依赖记录，
 * // 同时调用每个运行时 SurfaceAttributeCache.invalidateAll() 重新捕获属性。
 * }</pre>
 */
public final class SdfBakeData implements Savable {
    public static final String USER_DATA_KEY = "jme.sdf.bakeData";

    public enum Status {
        /** Layout and SDF usable; attributes match recorded material inputs. */
        VALID,
        /** SDF/layout usable, but recapture attributes before using cached material data. */
        MATERIAL_CHANGED,
        /** Geometry/hierarchy changed; rebake distance and mapping. */
        GEOMETRY_CHANGED,
        /** Explicit SDK invalidation; do not use until rebuilt. */
        INVALIDATED
    }

    private SdfVolume volume;
    private SurfaceCacheLayout layout;
    private String geometryHash;
    private String materialHash;
    private String dependencyRevision;
    private boolean invalidated;

    /** Deserialization only. */
    public SdfBakeData() {
    }

    /**
     * Generates without attaching or saving. Source must remain unchanged throughout.
     * Geometry signature conservatively includes all mesh buffers (including UVs).
     * @param model static model root
     * @param settings SDF resolution/sign policy
     * @param dependencies caller's external dependency content revision (never null)
     * @param progress optional SDF progress/cancellation callback
     * @return completed CPU-only bake data
     */
    public static SdfBakeData bake(Spatial model, SdfSettings settings, String dependencies,
            SdfProgressListener progress) {
        SdfBakeData data = new SdfBakeData();
        data.dependencyRevision = Objects.requireNonNull(dependencies, "dependencies");
        data.layout = SurfaceCacheLayout.bake(model);
        data.geometryHash = fingerprint(model, data.layout, false);
        data.materialHash = fingerprint(model, data.layout, true);
        data.volume = SdfGenerator.generate(model, settings, progress);
        return data;
    }

    /** @return immutable distance data, shared by instances with identical geometry */
    public SdfVolume getVolume() {
        return volume;
    }

    /** @return capture mapping, no texture allocation */
    public SurfaceCacheLayout getLayout() {
        return layout;
    }

    /**
     * Checks current source explicitly; may be expensive on large assets.
     * @param model source model
     * @param dependencies current external dependency content revision
     * @return validation state
     */
    public Status validate(Spatial model, String dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        if (invalidated) {
            return Status.INVALIDATED;
        }
        SurfaceCacheLayout current;
        try {
            current = SurfaceCacheLayout.bake(model);
        } catch (IllegalArgumentException exception) {
            return Status.GEOMETRY_CHANGED;
        }
        if (!geometryHash.equals(fingerprint(model, current, false))) {
            return Status.GEOMETRY_CHANGED;
        }
        if (!dependencyRevision.equals(dependencies) || !materialHash.equals(fingerprint(model, current, true))) {
            return Status.MATERIAL_CHANGED;
        }
        return Status.VALID;
    }

    /**
     * Records a material-only revision without rebaking SDF. Invalidates no runtime
     * objects automatically: owner must call cache.invalidateAll().
     * @param model source with unchanged geometry
     * @param dependencies new external content revision
     */
    public void refreshMaterials(Spatial model, String dependencies) {
        Status status = validate(model, dependencies);
        if (status == Status.GEOMETRY_CHANGED || status == Status.INVALIDATED) {
            throw new IllegalStateException("Geometry requires rebaking");
        }
        materialHash = fingerprint(model, layout, true);
        dependencyRevision = dependencies;
    }

    /** Marks persisted data unusable; a later successful bake replaces it. */
    public void invalidate() {
        invalidated = true;
    }

    /**
     * Attaches on scene-owning thread; preserves compatibility with SDF-only viewers.
     * @param model original root
     */
    public void attachTo(Spatial model) {
        model.setUserData(USER_DATA_KEY, this);
        volume.attachTo(model);
    }

    static String fingerprint(Spatial root, SurfaceCacheLayout layout, boolean materials) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Integer.toString(layout.getGeometryCount()).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < layout.getGeometryCount(); i++) {
                Geometry geometry = layout.resolve(root, i);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (materials) {
                    if (geometry.getMaterial() != null) {
                        com.jme3.material.Material material = geometry.getMaterial();
                        DataOutputStream stream = new DataOutputStream(bytes);
                        stream.writeUTF(String.valueOf(material.getMaterialDef().getAssetName()));
                        stream.writeBoolean(material.isTransparent());
                        java.util.List<com.jme3.material.MatParam> parameters = new java.util.ArrayList<>(material.getParams());
                        parameters.sort(java.util.Comparator.comparing(com.jme3.material.MatParam::getName));
                        for (com.jme3.material.MatParam parameter : parameters) {
                            stream.writeUTF(parameter.getName());
                            stream.writeUTF(parameter.getVarType().name());
                            Object value = parameter.getValue();
                            if (value instanceof com.jme3.texture.Texture) {
                                com.jme3.texture.Texture texture = (com.jme3.texture.Texture) value;
                                stream.writeUTF(texture.getMinFilter().name());
                                stream.writeUTF(texture.getMagFilter().name());
                                stream.writeUTF(texture.getWrap(com.jme3.texture.Texture.WrapAxis.S).name());
                                stream.writeUTF(texture.getWrap(com.jme3.texture.Texture.WrapAxis.T).name());
                                if (texture.getKey() != null) {
                                    // Asset content is covered by dependencyRevision. GPU upload and
                                    // material color-space normalization must not change this signature.
                                    BinaryExporter.getInstance().save(texture.getKey(), bytes);
                                } else {
                                    BinaryExporter.getInstance().save(texture.getImage(), bytes);
                                }
                            } else {
                                // MatParam handles primitive arrays by value rather than Object.toString identity.
                                BinaryExporter.getInstance().save(parameter, bytes);
                            }
                        }
                        BinaryExporter.getInstance().save(material.getAdditionalRenderState(), bytes);
                    }
                } else {
                    // Hash semantic source values, not exporter IDs/bounds/collision caches.
                    // Exporter object graphs can differ after an otherwise identical j3o round trip.
                    DataOutputStream stream = new DataOutputStream(bytes);
                    stream.writeUTF(layout.getPath(i));
                    stream.writeUTF(geometry.getMesh().getMode().name());
                    for (com.jme3.scene.VertexBuffer.Type type : com.jme3.scene.VertexBuffer.Type.values()) {
                        com.jme3.scene.VertexBuffer buffer = geometry.getMesh().getBuffer(type);
                        if (buffer != null && buffer.getData() != null) {
                            stream.writeUTF(type.name());
                            stream.writeUTF(buffer.getFormat().name());
                            stream.writeInt(buffer.getNumComponents());
                            stream.writeInt(buffer.getOffset());
                            stream.writeInt(buffer.getStride());
                            java.nio.Buffer values = buffer.getData();
                            stream.writeInt(values.limit());
                            for (int element = 0; element < values.limit(); element++) {
                                if (values instanceof java.nio.FloatBuffer) {
                                    stream.writeFloat(((java.nio.FloatBuffer) values).get(element));
                                } else if (values instanceof java.nio.IntBuffer) {
                                    stream.writeInt(((java.nio.IntBuffer) values).get(element));
                                } else if (values instanceof java.nio.ShortBuffer) {
                                    stream.writeShort(((java.nio.ShortBuffer) values).get(element));
                                } else if (values instanceof java.nio.ByteBuffer) {
                                    stream.writeByte(((java.nio.ByteBuffer) values).get(element));
                                } else if (values instanceof java.nio.DoubleBuffer) {
                                    stream.writeDouble(((java.nio.DoubleBuffer) values).get(element));
                                } else {
                                    throw new IllegalArgumentException("Unsupported mesh buffer " + values.getClass());
                                }
                            }
                        }
                    }
                    com.jme3.math.Matrix4f transform = layout.getGeometryToRoot(i);
                    for (int row = 0; row < 4; row++) {
                        for (int column = 0; column < 4; column++) {
                            stream.writeFloat(transform.get(row, column));
                        }
                    }
                }
                digest.update(bytes.toByteArray());
            }
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest()) {
                text.append(String.format("%02x", value & 255));
            }
            return text.toString();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot fingerprint bake source", exception);
        }
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule c = exporter.getCapsule(this);
        c.write(1, "version", 0);
        c.write(volume, "volume", null);
        c.write(layout, "layout", null);
        c.write(geometryHash, "geometryHash", null);
        c.write(materialHash, "materialHash", null);
        c.write(dependencyRevision, "dependencyRevision", null);
        c.write(invalidated, "invalidated", false);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule c = importer.getCapsule(this);
        if (c.readInt("version", 0) != 1) {
            throw new IOException("Unsupported SDF bake version");
        }
        volume = (SdfVolume) c.readSavable("volume", null);
        layout = (SurfaceCacheLayout) c.readSavable("layout", null);
        geometryHash = c.readString("geometryHash", null);
        materialHash = c.readString("materialHash", null);
        dependencyRevision = c.readString("dependencyRevision", null);
        invalidated = c.readBoolean("invalidated", false);
        if (volume == null || layout == null || geometryHash == null || materialHash == null || dependencyRevision == null) {
            throw new IOException("Incomplete SDF bake data");
        }
    }
}
