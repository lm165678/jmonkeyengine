package com.jme3.scene.plugins.sdf;

import com.jme3.export.*;
import com.jme3.scene.Spatial;
import java.io.IOException;
import java.util.Objects;

/**
 * Independent, persistent surface bake: layout/mapping + attribute pixels + depth.
 * Does not require or contain an SDF. No scene lighting is captured.
 * Source signatures refer to model-root-local geometry; root placement is excluded.
 *
 * <pre>{@code
 * // 第一步：按需独立烘焙，两者没有调用顺序要求。
 * SdfVolume sdf = SdfGenerator.bake(model, sdfSettings, progress);
 * SurfaceBakeData surfaces = SurfaceAttributeBaker.bake(
 *         model, dependencyRevision, renderManager, viewPort, 128, progress);
 * // 第二步：挂载到不同 userdata key。只需要 SDF 时完全省略 surfaces 的生成和挂载。
 * sdf.attachTo(model);
 * surfaces.attachTo(model);
 * // 第三步：一次保存 j3o。文件包含实际属性像素，不只是捕获布局。
 * BinaryExporter.getInstance().save(model, new File("Tank-baked.j3o"));
 * // 第四步：加载后分别获取。缺少任意一份都合法，不自动补烘焙。
 * SdfVolume loadedSdf = loaded.getUserData(SdfVolume.USER_DATA_KEY);
 * SurfaceBakeData loadedSurfaces = loaded.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * // 第五步：检查外部贴图/j3md/shader 内容版本，再上传已有属性，无需再次 capture。
 * if (loadedSurfaces.validate(loaded, dependencyRevision) != Status.VALID) {
 *     throw new IllegalStateException("表面属性已过期");
 * }
 * SurfaceAttributeData.Textures textures = loadedSurfaces.getAttributes().uploadAll();
 * // 后续查询：SDF 根局部命中 -> layout 候选 -> 卡片 UV/depth 校验 -> 属性。
 * // textures.close() 在渲染线程释放；不会删除 j3o 中的 CPU 数据。
 * }</pre>
 */
public final class SurfaceBakeData implements Savable {
    public static final String USER_DATA_KEY = "jme.surface.bakeData";

    public enum Status {
        /** Current geometry, material inputs and external content revision match. */
        VALID,
        /** Recapture attributes with current material/texture/shader inputs. */
        MATERIAL_CHANGED,
        /** Rebuild layout and attributes for changed geometry or descendant transforms. */
        GEOMETRY_CHANGED,
        /** Explicit editor invalidation. */
        INVALIDATED
    }

    private SurfaceCacheLayout layout;
    private SurfaceAttributeData attributes;
    private String geometryHash;
    private String triangleHash;
    private String materialHash;
    private String dependencyRevision;
    private boolean invalidated;

    /** Deserialization only; use SurfaceAttributeBaker.bake(). */
    public SurfaceBakeData() {
    }

    static SurfaceBakeData prepare(Spatial model, String revision) {
        SurfaceBakeData result = new SurfaceBakeData();
        result.dependencyRevision = Objects.requireNonNull(revision, "revision");
        result.layout = SurfaceCacheLayout.bake(model);
        result.geometryHash = SdfBakeData.fingerprint(model, result.layout, false);
        result.materialHash = SdfBakeData.fingerprint(model, result.layout, true);
        return result;
    }

    void complete(SurfaceAttributeData pixels) {
        if (pixels.getCardCount() != layout.getCardCount()) {
            throw new IllegalArgumentException("Surface pixels/layout mismatch");
        }
        attributes = pixels;
    }

    /** @return immutable capture mapping; no graphics context needed */
    public SurfaceCacheLayout getLayout() {
        return layout;
    }

    /** @return saved material/depth pixels, not lighting or scene radiance */
    public SurfaceAttributeData getAttributes() {
        if (attributes == null) {
            throw new IllegalStateException("Surface bake is incomplete");
        }
        return attributes;
    }

    /**
     * Explicit load/editor validation, not per-frame work. External content changes
     * at the same filenames must be represented in revision by the asset pipeline.
     * @param model original source geometry/hierarchy
     * @param revision external content revision
     * @return validation status
     */
    public Status validate(Spatial model, String revision) {
        Objects.requireNonNull(revision, "revision");
        if (invalidated) {
            return Status.INVALIDATED;
        }
        SurfaceCacheLayout current;
        try {
            current = SurfaceCacheLayout.bake(model);
        } catch (IllegalArgumentException exception) {
            return Status.GEOMETRY_CHANGED;
        }
        if (!geometryHash.equals(SdfBakeData.fingerprint(model, current, false))) {
            return Status.GEOMETRY_CHANGED;
        }
        if (!dependencyRevision.equals(revision) || !materialHash.equals(SdfBakeData.fingerprint(model, current, true))) {
            return Status.MATERIAL_CHANGED;
        }
        return Status.VALID;
    }

    /**
     * Verifies both independent bakes use the same triangle positions in the same
     * root-local coordinates. Does not imply complete surface coverage or valid materials.
     * Legacy SDF volumes without a source signature return false, rather than guessing.
     * <pre>{@code
     * if (!surfaceBake.isCompatibleWith(sdf)) {
     *     throw new IllegalStateException("SDF 与表面属性不是同一几何版本，请重新烘焙");
     * }
     * // 仍需 surfaceBake.validate(model, revision)，因为 UV/材质变化不一定改变 SDF。
     * }</pre>
     * @param sdf separately baked distance field
     * @return matching geometric source signatures
     */
    public boolean isCompatibleWith(SdfVolume sdf) {
        return !invalidated && sdf != null && triangleHash != null && triangleHash.equals(sdf.getSourceGeometryHash());
    }


    void setTriangleHash(String hash) {
        triangleHash = hash;
    }

    /** Editor marks this result stale; next successful bake returns a replacement. */
    public void invalidate() {
        invalidated = true;
    }

    /** @return explicit editor invalidation; not a substitute for validate(model, revision) */
    public boolean isInvalidated() {
        return invalidated;
    }

    /**
     * Attaches only the surface result; does not add, replace or remove an SDF.
     * Call on scene-owning thread after successful complete baking.
     * @param model same source root
     */
    public void attachTo(Spatial model) {
        getAttributes();
        model.setUserData(USER_DATA_KEY, this);
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        if (attributes == null) {
            throw new IOException("Cannot serialize incomplete surface bake");
        }
        OutputCapsule c = exporter.getCapsule(this);
        c.write(1, "version", 0);
        c.write(layout, "layout", null);
        c.write(attributes, "attributes", null);
        c.write(geometryHash, "geometryHash", null);
        c.write(triangleHash, "triangleHash", null);
        c.write(materialHash, "materialHash", null);
        c.write(dependencyRevision, "dependencyRevision", null);
        c.write(invalidated, "invalidated", false);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule c = importer.getCapsule(this);
        if (c.readInt("version", 0) != 1) {
            throw new IOException("Unsupported surface bake version");
        }
        layout = (SurfaceCacheLayout) c.readSavable("layout", null);
        attributes = (SurfaceAttributeData) c.readSavable("attributes", null);
        geometryHash = c.readString("geometryHash", null);
        triangleHash = c.readString("triangleHash", null);
        materialHash = c.readString("materialHash", null);
        dependencyRevision = c.readString("dependencyRevision", null);
        invalidated = c.readBoolean("invalidated", false);
        if (layout == null || attributes == null || geometryHash == null || triangleHash == null
                || materialHash == null || dependencyRevision == null || layout.getCardCount() != attributes.getCardCount()) {
            throw new IOException("Invalid surface bake metadata");
        }
    }
}
