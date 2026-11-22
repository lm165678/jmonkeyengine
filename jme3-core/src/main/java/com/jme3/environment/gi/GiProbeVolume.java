package com.jme3.environment.gi;

import com.jme3.export.*;
import com.jme3.math.Vector3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.scene.Node;
import java.io.IOException;

/**
 * An axis-aligned GI volume saved with the scene. PBR LightProbe is a separate light type.
 * Translation places the minimum corner; coverage and counts configure baking.
 * Defaults are a 16-unit local cube with 5 probes per axis. Positive world scale
 * multiplies coverage; rotation is unsupported. Transform changes require a new
 * bake; getData() returns null while placement differs from the completed bake. Close existing material bindings before
 * changing coverage/counts; either change invalidates this node's data reference.
 * Editing, baking and binding are render-thread operations. Rebasing the world
 * requires closing old material bindings and rebaking at the new placement.
 * Regular grids default to eight-corner interpolation with wrapped normal weights
 * with a continuous signed-plane attenuation and four-tap filtered distance moments. Variance merging preserves differences
 * between tap means; standard deviations must not be averaged as second moments.
 * Strict mixing has no minimum visibility; stabilized mixing remains optional.
 * setWideProbeSampling(true) selects the expensive 27-probe B-spline stencil,
 * strict front-plane gate and sixteen-tap visibility filtering. Free surface
 * layouts keep their existing radial stencil and ignore this quality option.
 * Old scenes without the new field use eight probes. Switching requires no
 * rebake and is published by automatic scene services on the next frame.
 *
 * <pre>{@code
 * // 第一步：添加可保存到场景的 GI 探针体积。
 * GiProbeVolume volume = new GiProbeVolume();
 * rootNode.attachChild(volume);
 * volume.setLocalTranslation(-8, 0, -8);
 * // 第二步：编辑覆盖尺寸和数量；修改后重新烘焙。
 * volume.configure(new Vector3f(16, 8, 16), 5, 3, 5);
 * // 第三步：缩放改变实际覆盖范围；此处最终覆盖 32 x 8 x 32，数量不变。
 * volume.setLocalScale(2, 1, 2);
 * rootNode.updateGeometricState();
 * // 第四步：使用 GiSceneBaker 烘焙，再将 data.bind 的句柄保留到卸载时。
 * // 平移或缩放后关闭旧绑定并重新烘焙；禁止旋转，也不支持负或零缩放。
 * // 第五步：更新引擎后，已有烘焙自动使用正面半球过滤，无需平滑开关或重烘焙。
 * }</pre>
 * The engine exposes this Node and its properties for editor integration; it
 * does not register an SDK palette entry. Unloading the node does not implicitly
 * dispose shared baked data or change materials.
 */
public final class GiProbeVolume extends Node {
    private Vector3f coverage = new Vector3f(16, 16, 16);
    private Vector3f counts = new Vector3f(5, 5, 5);
    private GiProbeData data;
    private Transform bakedTransform;
    private String streamAsset;
    private String bakedSkyState;
    private long streamBytes;
    private float intensity = 1;
    private boolean stabilizedBlending;
    private boolean wideProbeSampling;
    private boolean curvedReceiverCorrection;
    private boolean smoothBlending;
    private boolean surfaceLayout;
    private float fadeDistance;

    public GiProbeVolume() {
        super("GI Light Probe Volume");
    }

    /** Resizes coverage; a previous bake remains owned by the caller for disposal. */
    public void configure(Vector3f coverage, int x, int y, int z) {
        new GiProbeGrid(Vector3f.ZERO,
                new Vector3f(coverage.x / (x - 1), coverage.y / (y - 1), coverage.z / (z - 1)), x, y, z);
        this.coverage = coverage.clone();
        counts.set(x, y, z);
        data = null;
        streamAsset = null;
        streamBytes = 0;
        // Generated refinements describe the previous coverage/grid. Explicit
        // user-authored child nodes remain untouched.
        for (com.jme3.scene.Spatial child : new java.util.ArrayList<>(getChildren())) {
            if (Boolean.TRUE.equals(child.getUserData(GiAdaptiveProbeLayout.GENERATED))) {
                child.removeFromParent();
            }
        }
    }

    /**
     * Uses surface-conforming probes when enabled. Regular lattice probes are the default.
     * Counts define the spatial bin resolution and total probe-slot budget. Free
     * positions are sampled outside surfaces, with up to eight probes per bin.
     * Surface payloads use full-float storage even when compactStorage is requested,
     * preserving free offsets and bin indices. Sampling visits up to 27 bins with
     * eight entries each; it costs more than eight-corner lattice sampling.
     * Runtime uses compact-support distance/visibility interpolation, not trilinear
     * lattice weights. Empty space beyond probe support receives no volume GI.
     * This is a baked layout, not dynamic DDGI; changing it invalidates the bake.
     * Calls require the scene thread, outside rendering.
     * <pre>{@code
     * // 第一步：选择表面布局；保留默认 false 可使用原规则网格及细化块。
     * volume.setSurfaceLayout(true);
     * // 第二步：设置空间分桶和总预算，然后重新烘焙；安全距离使用 probeClearance。
     * volume.setProbeCounts(new Vector3f(12, 12, 12));
     * // 第三步：使用 GiSceneBaker 或 GiAdaptiveBakeJob；表面模式不生成规则细化块。
     * // 第四步：保存 J3O 和外部 GI 数据；SimpleApplication 自动恢复采样及流送。
     * // 恢复原布局：setSurfaceLayout(false)，重新烘焙，再保存。
     * }</pre>
     */
    public void setSurfaceLayout(boolean enabled) {
        if (surfaceLayout != enabled) {
            surfaceLayout = enabled;
            configure(coverage, (int) counts.x, (int) counts.y, (int) counts.z);
        }
    }

    public boolean isSurfaceLayout() {
        return surfaceLayout;
    }

    /** Scales diffuse GI at runtime. Defaults to 1 and must be finite and nonnegative.
     * Also updates generated adaptive child volumes. Does not change sky
     * visibility, direct light or invalidate the bake.
     * Change on the render thread and save the scene to persist it.
     * <pre>{@code
     * // 第一步：加载并挂载场景后取得 GiProbeVolume。
     * // 第二步：实时调整间接光，不需要重新烘焙。
     * volume.setIntensity(1.5f);
     * // 第三步：恢复物理基准强度；保存 J3O 会保留当前值。
     * volume.setIntensity(1f);
     * }</pre>
     */
    public void setIntensity(float value) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Finite nonnegative GI intensity required");
        }
        intensity = value;
        for (com.jme3.scene.Spatial child : getChildren()) {
            if (child instanceof GiProbeVolume
                    && Boolean.TRUE.equals(child.getUserData(GiAdaptiveProbeLayout.GENERATED))) {
                ((GiProbeVolume) child).setIntensity(value);
            }
        }
    }

    /**
     * Enables visibility-weight stabilization and square-root irradiance mixing.
     * Default false preserves strict visibility and linear mixing. This runtime
     * option does not change baked SH/distance data or enable dynamic DDGI.
     * The default eight-neighbor path uses a continuous linear visibility
     * confidence with a 5% fallback, without cubing/crushing weak confidence.
     * Wide and surface layouts retain their existing confidence weighting.
     * This favors smooth transitions over strict occlusion and may change local
     * contrast. No additional distance texture reads or rebake are required.
     * Valid but occluded probes receive a small weight: this can reduce black
     * patches but may increase thin-wall leakage. Invalid probes remain excluded.
     * Change on the render thread; generated adaptive children inherit the value.
     * Saved J3O scenes restore it through automatic scene services.
     * <pre>{@code
     * // 第一步：加载并挂接场景，取得需要调整的 GiProbeVolume。
     * rootNode.attachChild(assetManager.loadModel("Scenes/Room.j3o"));
     * // 第二步：渲染线程上启用稳定混合；无需重新烘焙。
     * volume.setStabilizedBlending(true);
     * // 第三步：对比漏光与黑块；恢复原模式并保存场景以保留选择。
     * volume.setStabilizedBlending(false);
     * }</pre>
     */
    public void setStabilizedBlending(boolean enabled) {
        stabilizedBlending = enabled;
        for (com.jme3.scene.Spatial child : getChildren()) {
            if (child instanceof GiProbeVolume
                    && Boolean.TRUE.equals(child.getUserData(GiAdaptiveProbeLayout.GENERATED))) {
                ((GiProbeVolume) child).setStabilizedBlending(enabled);
            }
        }
    }

    /**
     * Retained for old callers. The smooth mode caused light leaks, so this
     * setting and its old J3O flag are ignored. Visibility filtering works
     * with the current bake and does not use the old minimum weight.
     * @deprecated No replacement switch is needed.
     */
    @Deprecated
    public void setSmoothBlending(boolean enabled) {
        smoothBlending = false;
        for (com.jme3.scene.Spatial child : getChildren()) {
            if (child instanceof GiProbeVolume && Boolean.TRUE.equals(child.getUserData(GiAdaptiveProbeLayout.GENERATED))) {
                ((GiProbeVolume) child).setSmoothBlending(enabled);
            }
        }
    }

    public boolean isSmoothBlending() {
        return false;
    }

    /** Returns whether the optional 27-probe regular-grid stencil is enabled. */
    public boolean isWideProbeSampling() {
        return wideProbeSampling;
    }

    /** Selects the 27-probe stencil. The default uses eight probes.
     * Free surface layouts ignore this option. No rebake is needed. Change on
     * the owning scene thread; automatic services publish it at the next update.
     * The setting is saved in J3O and inherited by existing refinement children.
     * <pre>{@code
     * // 第一步：默认使用八探针，加载 J3O 后由内置服务自动接入。
     * volume.setWideProbeSampling(false);
     * // 第二步：需要宽邻域质量模式时开启，无需重新烘焙。
     * volume.setWideProbeSampling(true);
     * // 第三步：恢复低成本模式并保存场景，重新加载保留此设置。
     * volume.setWideProbeSampling(false);
     * }</pre>
     */
    public void setWideProbeSampling(boolean enabled) {
        wideProbeSampling = enabled;
        for (com.jme3.scene.Spatial child : getChildren()) {
            if (child instanceof GiProbeVolume) {
                ((GiProbeVolume) child).setWideProbeSampling(enabled);
            }
        }
    }

    /** @return whether curved receivers limit plane extrapolation; defaults to false */
    public boolean isCurvedReceiverCorrection() {
        return curvedReceiverCorrection;
    }

    /**
     * Limits plane extrapolation for curved receivers on the eight-probe grid.
     * The default (including old saves) uses the original plane algorithm.
     * Wide and surface layouts ignore this setting. No rebake is needed.
     * Smooth mesh normals are required; this is a heuristic, not exact transport.
     * Call on the owning render thread after attachment; save J3O to retain it.
     * <pre>{@code
     * // 第一步：先用默认 false 对照旧的墙面、墙脚和遮挡效果。
     * volume.setCurvedReceiverCorrection(false);
     * // 第二步：拱顶出现 GI 分面亮条时开启；自动场景服务下一帧更新绑定。
     * volume.setCurvedReceiverCorrection(true);
     * // 第三步：关闭立即恢复旧算法，无需修改或重新烘焙探针资源。
     * volume.setCurvedReceiverCorrection(false);
     * }</pre>
     * @param enabled true to limit plane projection on smooth curved receivers
     */
    public void setCurvedReceiverCorrection(boolean enabled) {
        curvedReceiverCorrection = enabled;
        for (com.jme3.scene.Spatial child : getChildren()) {
            if (child instanceof GiProbeVolume) {
                ((GiProbeVolume) child).setCurvedReceiverCorrection(enabled);
            }
        }
    }

    public boolean isStabilizedBlending() {
        return stabilizedBlending;
    }

    public float getIntensity() {
        return intensity;
    }

    /** Optional outer-boundary fade in world units, default 0 (no attenuation).
     * Scene volumes commonly end at walls: automatically fading 20% of their
     * bounds darkens those surfaces even with perfectly valid probe data.
     * For outdoor transitions set a positive distance explicitly; reset to zero
     * to restore full coverage. This only updates descriptors, never rebakes.
     * <pre>{@code
     * // 第一步：室内 Volume 不在墙面附近自动压暗。
     * volume.setFadeDistance(0f);
     * // 第二步：需要与无 GI 的外部区域渐变时，指定世界空间过渡宽度。
     * volume.setFadeDistance(2f);
     * }</pre>
     */
    public void setFadeDistance(float value) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Finite nonnegative fade distance required");
        }
        fadeDistance = value;
    }

    public float getFadeDistance() {
        return fadeDistance;
    }

    public Vector3f getCoverage() {
        return coverage.clone();
    }

    /** Updates the bounds without changing the probe counts. The volume must be baked again. */
    public void setCoverage(Vector3f coverage) {
        configure(coverage, (int) counts.x, (int) counts.y, (int) counts.z);
    }

    public Vector3f getProbeCounts() {
        return counts.clone();
    }

    /** Bean-style editor setter; components must be finite integer counts >= 2. */
    public void setProbeCounts(Vector3f counts) {
        if (!Vector3f.isValidVector(counts) || counts.x != (int) counts.x
                || counts.y != (int) counts.y || counts.z != (int) counts.z) {
            throw new IllegalArgumentException("Integer probe counts required");
        }
        configure(coverage, (int) counts.x, (int) counts.y, (int) counts.z);
    }

    public GiProbeGrid getGrid() {
        return new GiProbeGrid(Vector3f.ZERO, new Vector3f(coverage.x / (counts.x - 1),
                coverage.y / (counts.y - 1), coverage.z / (counts.z - 1)),
                (int) counts.x, (int) counts.y, (int) counts.z);
    }

    /** Returns an embedded completed bake, or null before baking, after resizing,
     * or when the payload is external. For external data use getStreamAsset()
     * and register a loader with GiProbeStreamer. This method does not read from disk.
     */
    public GiProbeData getData() {
        if (!getWorldRotation().equals(Quaternion.IDENTITY)) {
            return null;
        }
        if (bakedTransform == null) {
            return getWorldScale().equals(Vector3f.UNIT_XYZ) ? data : null;
        }
        return bakedTransform.equals(getWorldTransform()) ? data : null;
    }

    /**
     * Switches this node to a saved compressed bake and drops its reference to
     * the embedded CPU data. Does not perform IO or dispose shared data.
     * Call on the scene thread after a worker has atomically saved expected via
     * GiProbeData.saveCompressed. Stale bake completions return false.
     * <pre>{@code
     * // 第一步：渲染线程取得完成数据；后台线程保存独立 .giprobe 文件。
     * GiProbeData completed = volume.getData();
     * // completed.saveCompressed(output); // 在工作线程执行并原子提交文件。
     * // 第二步：回到渲染线程发布引用，再保存 j3o；不会再内嵌这份数据。
     * volume.useStreamAsset("GI/region-v2.giprobe", completed);
     * // 第三步：流送器注册该引用，工作线程通过 AssetManager.locateAsset
     * // 打开输入流并调用 GiProbeData.loadCompressed；数据随相机加载。
     * }</pre>
     * Existing embedded J3O files remain supported. Referenced files must ship
     * with the scene. Use immutable revisioned filenames for safe rebaking.
     */
    public boolean useStreamAsset(String assetKey, GiProbeData expected) {
        if (assetKey == null || assetKey.isEmpty() || assetKey.startsWith("/")
                || assetKey.contains("..") || assetKey.contains(":")) {
            throw new IllegalArgumentException("Project-relative GI asset key required");
        }
        if (expected == null || getData() != expected) {
            return false;
        }
        streamAsset = assetKey;
        streamBytes = expected.getTextureBytes();
        data = null;
        return true;
    }

    /** Valid external bake key, or null for embedded/unbaked/changed placement. */
    public String getStreamAsset() {
        return bakedTransform != null && bakedTransform.equals(getWorldTransform()) ? streamAsset : null;
    }

    /** Declared uncompressed texture payload bound for external chunk admission. */
    public long getStreamBytes() {
        return streamBytes;
    }

    /**
     * Builds a bake descriptor using world-scaled probe spacing. Call on the
     * render thread after geometric updates. Rotated volumes and volumes
     * with nonpositive scale cannot be baked.
     */
    public GiProbeGrid getBakeGrid() {
        Vector3f scale = getWorldScale();
        if (!getWorldRotation().equals(Quaternion.IDENTITY)
                || !Vector3f.isValidVector(scale) || scale.x <= 0 || scale.y <= 0 || scale.z <= 0) {
            throw new IllegalArgumentException("GI volumes require no world rotation and positive scale");
        }
        return new GiProbeGrid(Vector3f.ZERO, getGrid().getSpacing().mult(scale),
                (int) counts.x, (int) counts.y, (int) counts.z);
    }

    /**
     * Checks whether the completed sky bounce still matches the scene. The existing bake stays in use.
     * Legacy files report Unknown until rebaked. Call on the scene thread after
     * sky controls have updated. This scans the scene; do not call per material.
     * No volume or unloaded coverage means fully visible sky in the shader.
     * <pre>{@code
     * // 第一步：修改天光，直接天空照明立即变化。
     * sky.setIntensity(2f);
     * // 第二步：检查旧反弹光；显示提示，但继续使用旧结果。
     * String status = volume.getSkyBakeStatus();
     * // 第三步：手动重新运行 GiSceneBaker；成功后才替换结果并恢复 Current。
     * // 取消或失败保留旧数据；恢复原强度可恢复原状态（未重新捕捉时）。
     * }</pre>
     * @return No bake, Unknown (rebake), Current, or Stale (rebake)
     */
    public String getSkyBakeStatus() {
        if (data == null && streamAsset == null) {
            return "No bake";
        }
        if (bakedSkyState == null) {
            return "Unknown (rebake)";
        }
        com.jme3.scene.Spatial root = this;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return bakedSkyState.equals(GiSkyState.capture(root)) ? "Current" : "Stale (rebake)";
    }

    void publish(GiProbeData result, String skyState) {
        publish(result);
        bakedSkyState = skyState;
    }

    void publish(GiProbeData result) {
        bakedSkyState = null;
        data = result;
        streamAsset = null;
        streamBytes = 0;
        bakedTransform = getWorldTransform().clone();
    }

    /** Copies the editable settings and shares the read-only bake data. */
    @Override
    public void cloneFields(com.jme3.util.clone.Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        coverage = cloner.clone(coverage);
        counts = cloner.clone(counts);
        bakedTransform = cloner.clone(bakedTransform);
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        OutputCapsule out = exporter.getCapsule(this);
        out.write(coverage, "giCoverage", new Vector3f(16, 16, 16));
        out.write(counts, "giCounts", new Vector3f(5, 5, 5));
        out.write(data, "giData", null);
        out.write(streamAsset, "giStreamAsset", null);
        out.write(streamBytes, "giStreamBytes", 0L);
        out.write(bakedSkyState, "giSkyState", null);
        out.write(intensity, "giIntensity", 1f);
        out.write(smoothBlending, "giSmoothBlending", false);
        out.write(stabilizedBlending, "giStabilizedBlending", false);
        out.write(wideProbeSampling, "giWideProbeSampling", false);
        out.write(curvedReceiverCorrection, "giCurvedReceiverCorrection", false);
        out.write(surfaceLayout, "giSurfaceLayout", false);
        out.write(fadeDistance, "giFadeDistance", 0f);
        out.write(bakedTransform, "giBakeTransform", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        InputCapsule in = importer.getCapsule(this);
        coverage = (Vector3f) in.readSavable("giCoverage", new Vector3f(16, 16, 16));
        counts = (Vector3f) in.readSavable("giCounts", new Vector3f(5, 5, 5));
        data = (GiProbeData) in.readSavable("giData", null);
        streamAsset = in.readString("giStreamAsset", null);
        streamBytes = in.readLong("giStreamBytes", 0L);
        bakedSkyState = in.readString("giSkyState", null);
        setIntensity(in.readFloat("giIntensity", 1f));
        surfaceLayout = in.readBoolean("giSurfaceLayout", false);
        setSmoothBlending(in.readBoolean("giSmoothBlending", false));
        setStabilizedBlending(in.readBoolean("giStabilizedBlending", false));
        setWideProbeSampling(in.readBoolean("giWideProbeSampling", false));
        setCurvedReceiverCorrection(in.readBoolean("giCurvedReceiverCorrection", false));
        setFadeDistance(in.readFloat("giFadeDistance", 0f));
        bakedTransform = (Transform) in.readSavable("giBakeTransform", null);
        getGrid();
    }
}
