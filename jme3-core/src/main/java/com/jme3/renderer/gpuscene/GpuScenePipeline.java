package com.jme3.renderer.gpuscene;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.*;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.queue.*;
import com.jme3.scene.*;
import com.jme3.shader.BufferObject;
import com.jme3.shader.VarType;
import java.util.*;

/**
 * Indexed GPU instance culling and indirect drawing. Manually constructed
 * decorators start disabled; LegacyApplication's automatic AppSettings policy
 * requests enabled GPU Scene and Bindless by default on capable main viewports.
 * GPU visibility produces the existing instance-attribute ABI. Original material
 * definitions, techniques, light logic and render states perform all shading.
 * Materials advertise the UseInstancing contract; others remain on the original path.
 * Unsupported meshes retain their original render queue and material.
 * Skeletal meshes (including hardware bone buffers), morph meshes and transparent
 * or blended geometry deliberately stay on the traditional path, even before
 * animation controls have installed their shader parameters.
 * All methods are scene/render-thread only. Hierarchy and transform setters are
 * tracked incrementally in batches of at most 65,536 geometries. Unaffected
 * batches remain GPU-resident during attach/detach/reparent. Material setters and
 * geometry mesh/material/queue/cull setters are tracked. Use refreshSubtree after
 * in-place mesh edits. Light-list and control setters invalidate registration too.
 * Static instance records stay resident; moving objects invalidate their records.
 * Changed records are scattered by compute into resident storage, without a full
 * static-batch upload. Use normal Spatial setters; directly mutated transforms
 * require forceRefresh, just as with ordinary scene updates.
 * Control-free, fully supported subtrees bypass CPU queue traversal after registration.
 * Viewports with SceneProcessors retain legacy queue traversal for compatibility.
 * A plain Node root automatically receives an incremental child-update index
 * while enabled; disabling/cleanup restores the original index state. Existing
 * application-enabled indices and custom Node subclasses are left alone.
 * <pre>{@code
 * // 第一步：手动接管视口时创建管线；普通应用使用 AppSettings 自动策略即可。
 * GpuScenePipeline pipeline = new GpuScenePipeline(assetManager, models);
 * renderManager.setPipeline(viewPort, pipeline);
 * // 第二步：显式启用 GPU 实例剔除和间接绘制；需要 OpenGL 4.3，否则走原队列。
 * pipeline.setEnabled(true);
 * pipeline.setMaxDistance(200); // 0 禁用距离剔除，按包围球最近距离判断。
 * pipeline.setMinPixels(2); // 0 禁用屏幕尺寸剔除。
 * pipeline.setFrustumCulling(true);
 * // 第三步：移动模型后正常 updateGeometricState；GPU 数据随绘制更新。
 * // 第四步：关闭后使用原材质，释放 GPU 专用缓存，不修改源模型。
 * pipeline.setEnabled(false);
 * // 第五步：彻底恢复原 viewport 管线选择；引擎调用 cleanup。
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class GpuScenePipeline implements RenderPipeline {
    private final AssetManager assets;
    private final Spatial root;
    private final RenderPipeline shading;
    private final List<Batch> batches = new ArrayList<>();
    private final Set<Geometry> handled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Geometry, Batch> owners = new IdentityHashMap<>();
    private final Set<Spatial> ownedSubtrees = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Spatial> dirtySpatials = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Spatial> ownershipChanges = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Spatial> structuralChanges = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Batch> changedBatches = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Material, Material> canonicalMaterials = new IdentityHashMap<>();
    private final List<Material> materialRepresentatives = new ArrayList<>();
    private final Map<Material, MaterialWatch> materialWatches = new IdentityHashMap<>();
    private final Map<Geometry, Material> watchedGeometries = new IdentityHashMap<>();

    private static final class MaterialWatch {
        long revision;
        int state;
        final Set<Geometry> users = Collections.newSetFromMap(new IdentityHashMap<>());

        MaterialWatch(Material material) {
            revision = material.getContentRevision();
            state = material.getAdditionalRenderState().contentHashCode();
        }
    }

    @Override
    public void prepareSceneQueue() {
        shading.prepareSceneQueue();
        for (Map.Entry<Material, MaterialWatch> entry : materialWatches.entrySet()) {
            Material material = entry.getKey();
            MaterialWatch watch = entry.getValue();
            if (watch.revision != material.getContentRevision()
                    || watch.state != material.getAdditionalRenderState().contentHashCode()) {
                for (Geometry geometry : watch.users) {
                    refreshSubtree(geometry);
                }
            }
        }
    }

    private void watch(Geometry geometry) {
        Material material = geometry.getMaterial();
        if (material != null && !watchedGeometries.containsKey(geometry)
                && !geometry.isGrouped()
                && !(geometry instanceof com.jme3.scene.instancing.InstancedGeometry)
                && geometry.getMesh() != null && !geometry.getMesh().isAnimated()
                && geometry.getMesh().getMorphTargets().length == 0
                && material.getMaterialDef().getMaterialParam("UseInstancing") != null) {
            watchedGeometries.put(geometry, material);
            materialWatches.computeIfAbsent(material, MaterialWatch::new).users.add(geometry);
        }
    }

    private final SceneChangeListener sceneListener = (spatial, change) -> {
        if (change == SceneChangeListener.Change.ATTACHED || change == SceneChangeListener.Change.DETACHING
                || change == SceneChangeListener.Change.RENDERING) {
            structuralChanges.add(spatial);
            for (Spatial ancestor = spatial; ancestor != null; ancestor = ancestor.getParent()) {
                ownedSubtrees.remove(ancestor);
                ownershipChanges.add(ancestor);
            }
        } else if (change == SceneChangeListener.Change.TRANSFORM || change == SceneChangeListener.Change.BOUNDS) {
            enqueueDirty(spatial);
        }
    };
    private RenderManager manager;
    private ViewPort viewport;
    private boolean enabled;
    private long fullRegistrationCount;
    private long batchBuildCount;
    private boolean ownsUpdateIndex;
    private boolean frustum = true;
    private boolean occlusion;
    private boolean multiDrawIndirect;
    private boolean materialMerging;

    /**
     * Merges distinct materials only when definition, technique, parameter values
     * and render state are equivalent. Default false. Generic j3md materials work
     * without special shader code. Different colors/textures/state remain separate;
     * this does not imply a shader-specific GPU material parameter table.
     * Equivalence is cached by identity during registration, never by pixel-data
     * hashing per frame. Material setters are tracked automatically; in-place value
     * edits require reassignment or refreshSubtree. Render-thread only.
     * <pre>{@code
     * // 第一步：独立材质实例可保留各自所有权，不必手工替换为同一对象。
     * secondGeometry.setMaterial(firstGeometry.getMaterial().clone());
     * // 第二步：允许参数完全兼容的材质合批；纹理或颜色不同的材质仍分开。
     * pipeline.setMaterialMerging(true);
     * // 第三步：setter 修改材质会自动重新判断分组，避免沿用旧参数。
     * secondGeometry.getMaterial().setColor("Diffuse", ColorRGBA.Red);
     * // 第四步：关闭恢复按材质对象身份分组，不修改源材质。
     * pipeline.setMaterialMerging(false);
     * }</pre>
     */
    public void setMaterialMerging(boolean enabled) {
        if (materialMerging != enabled) {
            materialMerging = enabled;
            rebuild();
        }
    }

    /** @return whether value-equivalent materials may share an instance batch */
    public boolean isMaterialMerging() {
        return materialMerging;
    }

    private Material canonical(Material material) {
        if (!materialMerging) {
            return material;
        }
        Material cached = canonicalMaterials.get(material);
        if (cached != null) {
            return cached;
        }
        for (Material representative : materialRepresentatives) {
            if (representative.isReceivesShadows() == material.isReceivesShadows()
                    && representative.contentEquals(material)) {
                canonicalMaterials.put(material, representative);
                return representative;
            }
        }
        materialRepresentatives.add(material);
        canonicalMaterials.put(material, material);
        return material;
    }

    private boolean meshMerging;
    private boolean commandCompaction;

    /**
     * Requests GPU compaction of nonempty draw commands. Default false; requires
     * MDI and Caps.IndirectDrawCount. Unsupported hardware keeps ordinary MDI,
     * without reading a count back to the CPU. Material/state boundaries remain.
     * Compaction adds compute work and may not benefit short command lists.
     * Render-thread only; switching rebuilds owned buffers and clears frozen data.
     * <pre>{@code
     * // 第一步：启用 GPU Scene 和 MDI；可同时开启跨网格打包。
     * pipeline.setEnabled(true);
     * pipeline.setMultiDrawIndirect(true);
     * pipeline.setMeshMerging(true);
     * // 第二步：在 GPU 上移除 instanceCount 为零的命令，并由 GPU 决定提交数量。
     * pipeline.setCommandCompaction(true);
     * // 第三步：关闭作性能对照，普通 MDI 和剔除功能继续工作。
     * pipeline.setCommandCompaction(false);
     * // 第四步：移除管线时引擎释放缓冲；不要自行销毁 GL 对象。
     * renderManager.setPipeline(viewPort, null);
     * }</pre>
     * @param enabled whether empty commands should be removed on supported hardware
     */
    public void setCommandCompaction(boolean enabled) {
        if (commandCompaction != enabled) {
            commandCompaction = enabled;
            rebuild();
        }
    }

    /** @return requested mode; MDI and IndirectDrawCount are also required */
    public boolean isCommandCompaction() {
        return commandCompaction;
    }


    /**
     * Requests cross-mesh packing for groups sharing material identity (or equivalent
     * parameters when material merging is enabled), lights,
     * occlusion policies and compatible non-interleaved vertex layouts. Default
     * false. Effective only with multi-draw enabled; incompatible meshes remain
     * separate. Skeletal/morph/transparent objects retain their traditional path.
     * Source vertex/index buffers are untouched; packed storage is pipeline-owned
     * and released on rebuild/disable/cleanup. Render thread only; switching
     * rebuilds registration and invalidates frozen visibility.
     * <pre>{@code
     * // 第一步：不同网格共享同一个材质；不同渲染状态不会强制合并。
     * tankGeometry.setMaterial(sharedMaterial);
     * boatGeometry.setMaterial(sharedMaterial);
     * // 第二步：开启 MDI，再允许兼容顶点格式的网格打包到共享缓冲。
     * pipeline.setMultiDrawIndirect(true);
     * pipeline.setMeshMerging(true);
     * // 第三步：关闭跨网格合并作对照；同网格 LOD 的 MDI 仍可保留。
     * pipeline.setMeshMerging(false);
     * // 第四步：改动源网格数据后 rebuild，重新生成打包缓冲。
     * pipeline.rebuild();
     * }</pre>
     * @param enabled whether compatible distinct meshes may share a draw packet
     */
    public void setMeshMerging(boolean enabled) {
        if (meshMerging != enabled) {
            meshMerging = enabled;
            rebuild();
        }
    }

    /** @return requested cross-mesh mode; also requires multi-draw to be enabled */
    public boolean isMeshMerging() {
        return meshMerging;
    }

    private boolean bindlessTextures;

    /**
     * Requests bindless sampling on owned material clones. Default false; GPU Scene
     * remains usable with the option off or without ARB_bindless_texture support.
     * Original material definitions, lighting and render path remain unchanged.
     * Renderer-owned bounded snapshots retain mutable source textures; GPU-only
     * targets and cache exhaustion fall back to ordinary texture units. See
     * Material.setBindlessTextures for residency and memory details. Render-thread
     * only; changes rebuild registration and invalidate frozen visibility.
     * <pre>{@code
     * // 第一步：GPU Scene 和 Bindless 是独立开关，不支持 Bindless 不会关闭 GPU Scene。
     * pipeline.setEnabled(true);
     * pipeline.setBindlessTextures(true);
     * // 第二步：统计实际驻留纹理；数量为零可能是硬件/资源回退。
     * int resident = renderManager.getRenderer().getBindlessTextureCount();
     * // 第三步：恢复普通纹理绑定，实例化、GPU 剔除和间接绘制继续运行。
     * pipeline.setBindlessTextures(false);
     * }</pre>
     * @param enabled whether to request bindless material sampling
     */
    public void setBindlessTextures(boolean enabled) {
        if (bindlessTextures != enabled) {
            bindlessTextures = enabled;
            rebuild();
        }
    }

    /** @return requested bindless mode, not a guarantee that every texture uses a handle */
    public boolean isBindlessTextures() {
        return bindlessTextures;
    }


    /**
     * Enables shared-index LOD multi-draw submission. Disabled by default.
     * Material, mesh and light groups remain separate; this does not combine
     * incompatible shader/state bindings. Requires the same OpenGL 4.3 GPU Scene
     * capability; disabled GPU Scene retains its traditional rendering fallback.
     * Changing the option rebuilds registration and invalidates frozen visibility.
     * Render thread only. Packed indices are pipeline-owned; source assets are unchanged.
     * <pre>{@code
     * // 第一步：创建普通 GPU Scene，继续使用原材质和 Forward/Deferred 管线。
     * GpuScenePipeline gpu = new GpuScenePipeline(assetManager, models);
     * renderManager.setPipeline(viewPort, gpu);
     * gpu.setEnabled(true);
     * // 第二步：指定 GPU LOD 阈值，再合并同一批次的 LOD 间接命令。
     * gpu.setLodThresholds(80, 30, 10, 3);
     * gpu.setMultiDrawIndirect(true);
     * // 第三步：对照测试恢复逐条间接绘制；场景、材质及剔除配置保持不变。
     * gpu.setMultiDrawIndirect(false);
     * // 第四步：关闭 GPU Scene 则恢复传统绘制，由管线清理打包索引。
     * gpu.setEnabled(false);
     * }</pre>
     * @param enabled whether to combine compatible LOD commands
     */
    public void setMultiDrawIndirect(boolean enabled) {
        if (multiDrawIndirect != enabled) {
            multiDrawIndirect = enabled;
            rebuild();
        }
    }

    /** @return requested multi-draw mode; active GPU Scene also requires OpenGL 4.3 */
    public boolean isMultiDrawIndirect() {
        return multiDrawIndirect;
    }

    private java.util.function.Predicate<Geometry> occluderFilter = geometry -> true;
    private java.util.function.Predicate<Geometry> occlusionQueryFilter = geometry -> true;

    /**
     * Selects registered GPU geometry allowed to write Hi-Z occluder depth.
     * Null restores the default (all compatible geometry with PreShadow).
     * This does not disable normal rendering, shadows, or other culling tests.
     * Policies belong to this viewport pipeline, not to the Geometry globally.
     * Shadow processors retain their own caster selection and do not consume
     * this pipeline's main-view Hi-Z visibility.
     * Evaluated during registration, not every frame. Changing captured filter
     * state requires rebuild(). Setting a filter invalidates the frozen snapshot
     * and registration. Unsupported geometry stays on its existing render path.
     * Call on the render thread, or through Application.enqueue.
     * <pre>{@code
     * // 第一步：只让墙等大型物体写入遮挡深度，仍须提供正确的 PreShadow。
     * pipeline.setOccluderFilter(g -> Boolean.TRUE.equals(g.getUserData("Occluder")));
     * // 第二步：独立决定哪些物体接受查询；不查询不等于跳过视锥/距离剔除。
     * pipeline.setOcclusionQueryFilter(g -> !Boolean.TRUE.equals(g.getUserData("SkipHiZ")));
     * // 第三步：更新标记后通知管线重新注册；标记设置在实际 Geometry 上。
     * wall.setUserData("Occluder", true);
     * wall.setUserData("SkipHiZ", true);
     * pipeline.rebuild();
     * // 第四步：恢复默认，两类角色互相独立，可同时启用或同时关闭。
     * pipeline.setOccluderFilter(null);
     * pipeline.setOcclusionQueryFilter(null);
     * }</pre>
     * @param filter geometry selection, or null for the default
     */
    public void setOccluderFilter(java.util.function.Predicate<Geometry> filter) {
        occluderFilter = filter == null ? geometry -> true : filter;
        rebuild();
    }

    /**
     * Selects registered geometry subjected to Hi-Z queries, independently of
     * occluder participation. Null restores querying all compatible geometry.
     * Non-selected objects still use GPU instancing, LOD, frustum, distance and
     * screen-size tests. See setOccluderFilter for complete usage and lifecycle.
     * Registration evaluates this predicate once per geometry; captured state
     * changes require rebuild(). Must be called on the render thread.
     * @param filter query selection, or null for the default
     * @see #setOccluderFilter(java.util.function.Predicate)
     */
    public void setOcclusionQueryFilter(java.util.function.Predicate<Geometry> filter) {
        occlusionQueryFilter = filter == null ? geometry -> true : filter;
        rebuild();
    }

    private HiZPyramid hiz;
    private HiZMode hiZMode = HiZMode.SAME_FRAME;
    private boolean historyValid;
    private final Matrix4f historyViewProjection = new Matrix4f();

    /**
     * Selects the main-view Hi-Z schedule; SAME_FRAME is the default. This option
     * has no effect while occlusion is disabled. HISTORY_TWO_PHASE uses history
     * only to select occluders, renders their current transforms, then retests
     * every query candidate using current depth. Missing occluders can reduce
     * culling efficiency but cannot hide newly exposed geometry. Query-exempt
     * objects bypass both queries. Shadows never consume these visibility lists.
     * This is a reduced occluder prepass, not reuse of the final scene framebuffer:
     * that framebuffer may include objects excluded by the occluder policy.
     * First frame, resize and rebuild use full eligible current-frame occluders.
     * Setting the mode releases history/registration and invalidates a frozen
     * snapshot. Render thread only; graphics resources remain pipeline-owned.
     * <pre>{@code
     * // 第一步：显式启用主视图 Hi-Z；关闭时任何模式都不会生成深度金字塔。
     * pipeline.setOcclusionCulling(true);
     * // 第二步：历史筛选遮挡体，再用当前深度补测全部候选，不直接沿用历史可见性。
     * pipeline.setHiZMode(HiZMode.HISTORY_TWO_PHASE);
     * // 第三步：保持相机和场景一致，切回同帧模式比较整帧与深度绘制耗时。
     * pipeline.setHiZMode(HiZMode.SAME_FRAME);
     * // 第四步：完全关闭 Hi-Z，保留独立的视锥/距离/屏幕尺寸 GPU 剔除。
     * pipeline.setOcclusionCulling(false);
     * }</pre>
     * @param mode non-null scheduling mode
     */
    public void setHiZMode(HiZMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (hiZMode != mode) {
            hiZMode = mode;
            rebuild();
        }
    }

    /** @return selected schedule; occlusion must also be enabled to execute it */
    public HiZMode getHiZMode() {
        return hiZMode;
    }

    private boolean hizDiagnosticsEnabled;
    private HiZDiagnostics hizDiagnostics;
    private boolean freezeCulling;
    private boolean frozenSnapshot;
    private Camera frozenCamera;

    /**
     * Requests a debug visibility freeze, disabled by default. The next render
     * captures current instance transforms, Hi-Z and indirect commands; subsequent
     * renders use that snapshot while the viewing camera remains free to move.
     * This freezes GPU-managed geometry only, not simulation, fallback geometry,
     * material animation or lights. Scene mutations accumulate until unfreeze.
     * Resource-rebuilding configuration changes capture a new snapshot. Render thread only.
     * <pre>{@code
     * // 第一步：在需要观察的位置按键请求冻结，下一次绘制捕获完整快照。
     * pipeline.setCullingFrozen(true);
     * // 第二步：等待 isCullingSnapshotFrozen，再移动观察相机查看保留/剔除结果。
     * if (pipeline.isCullingSnapshotFrozen()) {
     *     Camera captureCamera = pipeline.getFrozenCullingCamera();
     * }
     * // 第三步：冻结期间深度纹理和诊断数量保持对应，样本 age 会增长。
     * // 第四步：解除冻结，下一帧重新处理累积的实例变化和当前相机。
     * pipeline.setCullingFrozen(false);
     * }</pre>
     * @param frozen capture and hold the GPU visibility snapshot
     */
    public void setCullingFrozen(boolean frozen) {
        if (freezeCulling != frozen) {
            freezeCulling = frozen;
            frozenSnapshot = false;
            frozenCamera = null;
            if (hizDiagnostics != null) {
                hizDiagnostics.close();
                hizDiagnostics = null;
            }
        }
    }

    /** @return whether a requested frozen snapshot has actually been captured */
    public boolean isCullingSnapshotFrozen() {
        return frozenSnapshot;
    }

    /** @return a copy of the capture camera, or null before capture; viewing camera is unaffected */
    public Camera getFrozenCullingCamera() {
        return frozenCamera == null ? null : frozenCamera.clone();
    }

    /**
     * Enables optional Hi-Z diagnostics, disabled by default. Samples at most four
     * frames per second using asynchronous command copies and GPU elapsed queries.
     * No blocking readback is performed; a sample stays pending until all results
     * are ready. Active external elapsed-time profiling suppresses new samples to
     * avoid nested queries. Render thread only; disabling releases diagnostic data.
     * <pre>{@code
     * // 第一步：启用遮挡，再按需开启诊断；正式运行可以关闭诊断。
     * pipeline.setOcclusionCulling(true);
     * pipeline.setHiZDiagnosticsEnabled(true);
     * // 第二步：每帧读取已完成的快照；null 表示尚无结果，不要等待。
     * HiZStatistics sample = pipeline.getHiZStatistics();
     * if (sample != null) {
     *     long rejected = sample.getOccluded();
     *     double gpuMs = sample.getTotalMilliseconds();
     * }
     * // 第三步：可视化工具借用某层深度纹理，不拥有/销毁它。
     * int count = pipeline.getHiZLevelCount();
     * if (count > 0) {
     *     Texture2D level = pipeline.getHiZLevel(0);
     * }
     * // 第四步：关闭诊断不会关闭遮挡剔除。
     * pipeline.setHiZDiagnosticsEnabled(false);
     * }</pre>
     * @param enabled collect nonblocking Hi-Z count/timing samples
     */
    public void setHiZDiagnosticsEnabled(boolean enabled) {
        hizDiagnosticsEnabled = enabled;
        if (!enabled && hizDiagnostics != null) {
            hizDiagnostics.close();
            hizDiagnostics = null;
        }
    }

    /** @return last completed sample, or null when disabled/not ready; see diagnostic usage above */
    public HiZStatistics getHiZStatistics() {
        return hizDiagnostics == null ? null : hizDiagnostics.latest();
    }

    /** @return available pyramid levels; zero before the first Hi-Z render or after disable */
    public int getHiZLevelCount() {
        return hiz == null ? 0 : hiz.levels.size();
    }

    /**
     * Borrows a pyramid texture for debug display. Level zero is standard hardware
     * depth; subsequent levels store the maximum of their covered depth samples.
     * Valid only until resize, rebuild, disable or cleanup. Reacquire every frame.
     * See setHiZDiagnosticsEnabled for a complete usage example; render thread only.
     * @param level index in [0, getHiZLevelCount())
     * @return borrowed texture; do not dispose or change sampler settings
     */
    public com.jme3.texture.Texture2D getHiZLevel(int level) {
        if (hiz == null || level < 0 || level >= hiz.levels.size()) {
            throw new IllegalArgumentException("Hi-Z level unavailable");
        }
        return hiz.levels.get(level);
    }
    private boolean rebuild = true;
    private float maxDistance;
    private float minPixels;
    private float[] lodThresholds = new float[0];

    /**
     * Selects existing mesh LODs by projected bounding-sphere diameter in pixels.
     * Empty thresholds (default) use LOD0. Up to seven strictly decreasing positive
     * thresholds select up to eight levels. Missing mesh levels clamp to the last
     * available level; meshes without LOD data use their original indices. Changes
     * rebuild owned GPU buffers on the render thread. No mesh simplification occurs
     * at runtime. Each level reserves an instance-output segment, trading memory for
     * GPU-only compaction without readback. Transitions are discrete, without fading.
     * <pre>{@code
     * // 第一步：加载包含离线 LOD 索引的 j3o，材质不需要特殊 LOD 着色器。
     * Spatial model = assetManager.loadModel("Models/Tank/LodTank.j3o");
     * // 第二步：80px 以上使用 LOD0，依次在 80/30/10/3px 切换到后续级别。
     * pipeline.setLodThresholds(80, 30, 10, 3);
     * // 第三步：可独立关闭距离剔除；LOD 仍会降低远处模型的面数。
     * pipeline.setMaxDistance(0);
     * // 第四步：传空参数恢复 LOD0，便于同相机画质/性能对比。
     * pipeline.setLodThresholds();
     * }</pre>
     * @param pixels decreasing screen-diameter thresholds, copied by the pipeline
     */
    public void setLodThresholds(float... pixels) {
        Objects.requireNonNull(pixels);
        if (pixels.length > 7) {
            throw new IllegalArgumentException("At most seven LOD transitions");
        }
        float previous = Float.POSITIVE_INFINITY;
        for (float value : pixels) {
            if (!Float.isFinite(value) || value <= 0 || value >= previous) {
                throw new IllegalArgumentException("LOD thresholds must be positive and strictly decreasing");
            }
            previous = value;
        }
        if (!Arrays.equals(lodThresholds, pixels)) {
            lodThresholds = pixels.clone();
            clear();
        }
    }

    public GpuScenePipeline(AssetManager assets, Spatial root) {
        this(assets, root, new com.jme3.renderer.pipeline.ForwardPipeline());
    }

    /**
     * Decorates a separately owned shading pipeline, without replacing material techniques.
     * <pre>{@code
     * // 第一步：选择着色路径，GPU 剔除与 Forward/Deferred 无关。
     * RenderPipeline shading = new DeferredPipeline(assetManager, renderManager.getDeferredShadingModels());
     * // 第二步：装饰这条路径，只改变队列提交方式；关闭 GPU 后仍用同一 shading。
     * GpuScenePipeline gpu = new GpuScenePipeline(assetManager, models, shading);
     * renderManager.setPipeline(viewPort, gpu);
     * gpu.setEnabled(true);
     * gpu.setEnabled(false); // 着色路径保持不变。
     * // 第三步：不要另行安装/清理 shading，生命周期由 gpu 装饰器负责。
     * }</pre>
     * @param assets asset manager
     * @param root borrowed scene subtree
     * @param shading exclusively owned downstream shading pipeline
     */
    public GpuScenePipeline(AssetManager assets, Spatial root, RenderPipeline shading) {
        this.shading = Objects.requireNonNull(shading);
        this.assets = Objects.requireNonNull(assets);
        this.root = Objects.requireNonNull(root);
    }

    /**
     * Returns the borrowed downstream shading pipeline for diagnostics/configuration.
     * Never initialize/cleanup/install it separately: this decorator owns lifecycle.
     * <pre>{@code
     * // 第一步：获取当前视口管线；自动 GPU Scene 是外层装饰器。
     * RenderPipeline selected = renderManager.getPipeline(viewPort);
     * // 第二步：穿过装饰器查看实际 Forward/Deferred 管线，不改变其所有权。
     * while (selected instanceof GpuScenePipeline) {
     *     selected = ((GpuScenePipeline) selected).getShadingPipeline();
     * }
     * }</pre>
     * @return downstream shading pipeline, never null
     */
    public RenderPipeline getShadingPipeline() {
        return shading;
    }

    /** Enables the opt-in pipeline; false releases exclusive GPU resources immediately. */
    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            clear();
            if (root.getClass() == Node.class) {
                Node node = (Node) root;
                if (enabled && !node.isIncrementalUpdatesEnabled()) {
                    node.setIncrementalUpdatesEnabled(true);
                    ownsUpdateIndex = true;
                } else if (!enabled && ownsUpdateIndex) {
                    node.setIncrementalUpdatesEnabled(false);
                    ownsUpdateIndex = false;
                }
            }
        }
    }

    /**
     * CPU-only lifetime counter of full scene registrations, including explicit
     * rebuild/enable cycles. It is not reset on disable and never reads GPU data.
     * Attach/detach should only increase batch builds, not this counter.
     * Read on the application/render thread after the frame has rendered.
     * <pre>{@code
     * // 第一步：记录当前计数，然后挂载新场景块。
     * long previous = pipeline.getFullRegistrationCount();
     * world.attachChild(chunk);
     * // 第二步：下一帧渲染结束后读取，应与 previous 相同（增量注册）。
     * long current = pipeline.getFullRegistrationCount();
     * long rebuiltBatches = pipeline.getBatchBuildCount();
     * // 第三步：诊断只读，无 GPU 等待，不需要释放任何对象。
     * }</pre>
     * @return cumulative full registrations for this pipeline instance
     */
    public long getFullRegistrationCount() {
        return fullRegistrationCount;
    }

    /**
     * CPU-only cumulative count of initialized/rebuilt batches. See
     * getFullRegistrationCount for the streaming diagnostic example. Render-thread
     * reads; no reset on disable, GPU synchronization or ownership transfer.
     * @return batch builds including first registration and structural edits
     */
    public long getBatchBuildCount() {
        return batchBuildCount;
    }

    /** @return explicit request; hardware support is reported by isActive */
    public boolean isEnabled() {
        return enabled;
    }

    /** @return whether enabled and the current backend supports the GPU path */
    public boolean isActive() {
        return enabled && manager != null && manager.getRenderer().getCaps().contains(Caps.OpenGL43);
    }

    /**
     * Returns registered geometry instances without synchronizing with the GPU.
     * This is an input count, not the post-culling visible count; populated after
     * the first active render. Render thread only.
     * <pre>{@code
     * // 第一步：在 simpleRender 中读取输入规模，不执行 GPU 回读。
     * int submitted = pipeline.getInstanceCount();
     * // 第二步：同时检查批次数；一个模型可能包含多个 Geometry。
     * int batches = pipeline.getBatchCount();
     * }</pre>
     * @return registered geometry count, zero when disabled or not yet built
     */
    public int getInstanceCount() {
        return handled.size();
    }

    /**
     * Returns indirect batches, not total render passes or driver draw calls.
     * Use with {@link #getInstanceCount()} as shown in its usage example.
     * @return current mesh/material/light grouping count
     */
    public int getBatchCount() {
        return batches.size();
    }

    /**
     * Synchronously reads the number of submitted indirect commands for diagnostics.
     * Unlike API draw calls, this counts each command within MDI. Empty commands
     * are included when compaction is disabled/unavailable. Not a per-frame HUD API:
     * readback can stall. Call after rendering, outside performance measurements.
     * <pre>{@code
     * // 第一步：完成至少一帧绘制；未注册时返回零。
     * // 第二步：仅在验证阶段读取，比较压缩开关前后的命令数量。
     * int submitted = pipeline.readSubmittedCommandCount();
     * // 第三步：正常运行时停止读取，避免 CPU 等待 GPU。
     * }</pre>
     * @return commands submitted per opaque draw of this viewport
     */
    public int readSubmittedCommandCount() {
        int total = 0;
        java.nio.ByteBuffer bytes = com.jme3.util.BufferUtils.createByteBuffer(4);
        for (Batch batch : batches) {
            if (batch.plan == null) {
                continue;
            }
            if (batch.compact == null) {
                total += batch.proxies.size();
            } else {
                bytes.clear();
                manager.getRenderer().readBufferData(batch.drawCount, 0, bytes);
                total += bytes.getInt(0);
            }
        }
        return total;
    }

    /**
     * Blocking diagnostic readback, never required by rendering. Call after drawing.
     * <pre>{@code
     * // 测试模式在 simpleRender 中读取 Compute 写入的真实可见数量。
     * int visible = pipeline.readVisibleCount();
     * // 正常游戏不要逐帧调用：此方法会等待 GPU，影响性能测量。
     * }</pre>
     * @return visible geometry instances summed over all indirect batches
     */
    public int readVisibleCount() {
        return (int) readCommandTotal(false);
    }

    /**
     * Reads triangles submitted by the current indirect commands, once per batch.
     * This blocking diagnostic excludes fallback geometry and additional shading,
     * shadow or depth passes. It does not count rasterized fragments. Render thread
     * only, after drawing; zero if disabled. Never use in a timed frame loop.
     * <pre>{@code
     * // 第一步：完成固定相机的计时区间，再读取绘制工作量。
     * long triangles = pipeline.readVisibleTriangleCount();
     * // 第二步：改变距离剔除配置，重新预热和计时，比较两次三角形数量。
     * pipeline.setMaxDistance(0);
     * // 第三步：读取会同步 GPU；不要把该帧计入性能均值。
     * }</pre>
     * @return submitted triangle count for one execution of each indirect command
     */
    public long readVisibleTriangleCount() {
        return readCommandTotal(true);
    }

    private long readCommandTotal(boolean triangles) {
        if (!isActive()) {
            return 0;
        }
        java.nio.ByteBuffer bytes = com.jme3.util.BufferUtils.createByteBuffer(8);
        long count = 0;
        for (Batch batch : batches) {
            bytes.clear();
            for (int level = 0; level < batch.proxies.size(); level++) {
                bytes.clear();
                manager.getRenderer().readBufferData(batch.commands, level * 20L, bytes);
                count += (long) bytes.getInt(4) * (triangles ? bytes.getInt(0) / 3 : 1);
            }
        }
        com.jme3.util.BufferUtils.destroyDirectBuffer(bytes);
        return count;
    }

    /**
     * Enables current-frame standard-depth Hi-Z occlusion; default false.
     * Only materials providing PreShadow participate as depth occluders. Others
     * remain visible candidates. SAME_FRAME is the default; setHiZMode can select
     * history-assisted occluder selection. Both retain an occluder depth prepass.
     * <pre>{@code
     * // 第一步：开启 GPU Scene，再按需打开当前帧 Hi-Z。
     * pipeline.setEnabled(true);
     * pipeline.setOcclusionCulling(true);
     * // 第二步：自定义材质提供正确的 PreShadow 技术，包括位移/alpha 测试。
     * // 未提供该技术的模型不贡献遮挡深度，不会被强制换成简化材质。
     * // 第三步：关闭遮挡仅跳过深度链路，其他 GPU 剔除仍可用。
     * pipeline.setOcclusionCulling(false);
     * }</pre>
     * @param enabled current-frame depth occlusion
     */
    public void setOcclusionCulling(boolean enabled) {
        if (occlusion != enabled) {
            setCullingFrozen(false);
        }
        occlusion = enabled;
        if (!enabled && hizDiagnostics != null) {
            hizDiagnostics.close();
            hizDiagnostics = null;
        }
        if (!enabled && hiz != null) {
            hiz.close();
            hiz = null;
        }
    }

    /** @param distance world-space cutoff, zero disables; default zero */
    public void setMaxDistance(float distance) {
        if (!Float.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("Invalid distance");
        }
        maxDistance = distance;
    }

    /** @param pixels minimum projected sphere diameter; zero disables, default zero */
    public void setMinPixels(float pixels) {
        if (!Float.isFinite(pixels) || pixels < 0) {
            throw new IllegalArgumentException("Invalid pixel cutoff");
        }
        minPixels = pixels;
    }

    /** @param enabled enables conservative GPU frustum tests; default true */
    public void setFrustumCulling(boolean enabled) {
        frustum = enabled;
    }

    /**
     * Re-registers one subtree after material, mesh, light or queue-policy edits.
     * Normal attach/detach/reparent and material/queue setters are automatic; movement
     * uses transform setters and never needs this call. Registration occurs after
     * geometric state update, before drawing. Unaffected batches remain resident;
     * affected batches (maximum 65,536 geometries) are rebuilt. Render-thread only.
     * <pre>{@code
     * // 第一步：后台读取场景块后，在应用线程挂载；自动增量注册。
     * app.enqueue(() -> {

     *     world.attachChild(loadedChunk);

     *     return null;

     * });
     * // 第二步：卸载时正常 detach，自动移除实例并释放空批次的专用缓冲。
     * world.detachChild(oldChunk);
     * // 第三步：如果修改已注册对象的材质/网格/队列策略，只刷新受影响子树。
     * geometry.setMaterial(replacementMaterial);
     * pipeline.refreshSubtree(geometry);
     * // 第四步：移动不需要刷新注册，正常变换 API 即可。
     * loadedChunk.move(10, 0, 0);
     * }</pre>
     * @param subtree subtree whose registration policy or resources changed
     */
    public void refreshSubtree(Spatial subtree) {
        Objects.requireNonNull(subtree, "subtree");
        structuralChanges.add(subtree);
        for (Spatial ancestor = subtree; ancestor != null; ancestor = ancestor.getParent()) {
            ownedSubtrees.remove(ancestor);
            ownershipChanges.add(ancestor);
        }
    }

    /**
     * Invalidates registration and releases exclusive GPU resources. Normal
     * hierarchy/material/light/control/queue setters already invalidate automatically.
     * Use this for deliberate full cache reset, or refreshSubtree for in-place edits.
     * Render thread only; do not call every frame or for ordinary transform setters.
     * Conservative batch bounds become tight again at the next registration.
     * <pre>{@code
     * // 第一步：正常运动直接使用场景 API，增量更新不需要 rebuild。
     * model.setLocalTranslation(10, 0, 0);
     * // 第二步：材质/队列 setter 自动重新判断兼容路径。
     * geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
     * // 第三步：确实需要全量重置缓存时才调用，不要每帧调用。
     * pipeline.rebuild();
     * // 第三步：下一帧自动注册；无需手动初始化/上传 GPU 资源。
     * }</pre>
     */
    public void rebuild() {
        clear();
    }

    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        this.manager = manager;
        this.viewport = viewport;
        shading.initialize(manager, viewport);
        root.addSceneChangeListener(sceneListener);
    }

    @Override
    public boolean isInitialized() {
        return manager != null;
    }

    @Override
    public void reshape(int width, int height) {
        historyValid = false;
        shading.reshape(width, height);
    }

    @Override
    public void render(RenderQueue queue) {
        prepareQueue(queue);
        shading.render(queue);
    }

    @Override
    public boolean handlesSceneQueue(Spatial subtree) {
        return isActive() && !rebuild && viewport.getProcessors().isEmpty()
                && (ownedSubtrees.contains(subtree) || shading.handlesSceneQueue(subtree));
    }

    @Override
    public void appendPasses(com.jme3.renderer.framegraph.FrameGraph graph, RenderQueue queue) {
        graph.addPass("GpuScene/CullAndCompact", context -> prepareQueue(queue)).sideEffect();
        graph.addExpansionPass("GpuScene/Shading", current -> shading.appendPasses(current, queue));
    }

    private void prepareQueue(RenderQueue queue) {
        if (!isActive()) {
            return;
        }
        if (!freezeCulling || !frozenSnapshot || !structuralChanges.isEmpty()) {
            updateVisibility();
            if (freezeCulling) {
                frozenSnapshot = true;
                frozenCamera = viewport.getCamera().clone();
            }
        } else if (hizDiagnostics != null) {
            hizDiagnostics.poll();
        }
        submitQueue(queue);
    }

    private void updateVisibility() {
        if (rebuild) {
            clear();
            collect(root);
            fullRegistrationCount++;
            indexOwnedSubtrees(root);
            rebuild = false;
        }
        applyStructuralChanges();
        for (Spatial spatial : dirtySpatials) {
            markDirty(spatial);
        }
        dirtySpatials.clear();
        manager.setCamera(viewport.getCamera(), false);
        manager.setWorldMatrix(Matrix4f.IDENTITY);
        for (Batch batch : batches) {
            batch.prepare(false);
        }
        if (occlusion && !batches.isEmpty()) {
            if (hizDiagnosticsEnabled && hizDiagnostics == null) {
                hizDiagnostics = new HiZDiagnostics(manager.getRenderer());
            }
            if (hizDiagnostics != null) {
                hizDiagnostics.beginSample(freezeCulling);
                for (Batch batch : batches) {
                    hizDiagnostics.snapshot(batch.commands, batch.proxies.size(), false,
                            batch.occluder && batch.proxy.getMaterial().getMaterialDef().getTechniqueDefs("PreShadow") != null);
                }
            }
            if (hizDiagnostics != null) {
                hizDiagnostics.start(3);
            }
            try {
                Camera camera = viewport.getCamera();
                boolean useHistory = hiZMode == HiZMode.HISTORY_TWO_PHASE && historyValid
                        && hiz != null && hiz.width == camera.getWidth() && hiz.height == camera.getHeight();
                if (useHistory) {
                    for (Batch batch : batches) {
                        if (batch.occluder && batch.queryOcclusion) {
                            batch.prepare(true, historyViewProjection);
                        }
                    }
                }
            } finally {
                if (hizDiagnostics != null) {
                    hizDiagnostics.stop();
                }
            }
            if (hizDiagnostics != null) {
                for (Batch batch : batches) {
                    hizDiagnostics.snapshotDepth(batch.commands, batch.proxies.size());
                }
            }
            buildHiZ();
            historyViewProjection.set(viewport.getCamera().getViewProjectionMatrix());
            historyValid = true;
            if (hizDiagnostics != null) {
                hizDiagnostics.start(2);
            }
            try {
                for (Batch batch : batches) {
                    // Query-exempt batches retain the first cull/LOD output.
                    // Do not repeat their dispatch or command-buffer reset.
                    if (batch.queryOcclusion) {
                        batch.prepare(true);
                    }
                }
            } finally {
                if (hizDiagnostics != null) {
                    hizDiagnostics.stop();
                }
            }
            if (hizDiagnostics != null) {
                for (Batch batch : batches) {
                    hizDiagnostics.snapshot(batch.commands, batch.proxies.size(), true, false);
                }
                hizDiagnostics.finishSample();
            }
        }
    }

    private static int depth(Spatial spatial) {
        int depth = 0;
        for (Spatial parent = spatial.getParent(); parent != null; parent = parent.getParent()) {
            depth++;
        }
        return depth;
    }

    private boolean belongsToRoot(Spatial spatial) {
        for (Spatial ancestor = spatial; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor == root) {
                return true;
            }
        }
        return false;
    }

    private void unregister(Spatial spatial) {
        ownedSubtrees.remove(spatial);
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            Material oldMaterial = watchedGeometries.remove(geometry);
            if (oldMaterial != null) {
                MaterialWatch watch = materialWatches.get(oldMaterial);
                watch.users.remove(geometry);
                if (watch.users.isEmpty()) {
                    materialWatches.remove(oldMaterial);
                }
            }
            Batch batch = owners.remove(geometry);
            handled.remove(geometry);
            if (batch != null) {
                batch.indices.remove(geometry);
                changedBatches.add(batch);
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                unregister(child);
            }
        }
    }

    // Rebuild only touched batches, not unrelated resident world chunks. This
    // handles attach/detach/reparent coalesced within a frame using final ancestry.
    private void applyStructuralChanges() {
        if (structuralChanges.isEmpty()) {
            return;
        }
        historyValid = false;
        frozenSnapshot = false;
        canonicalMaterials.clear();
        materialRepresentatives.clear();
        for (Spatial spatial : structuralChanges) {
            unregister(spatial);
        }
        for (Batch batch : changedBatches) {
            batch.instances.removeIf(geometry -> !batch.indices.containsKey(geometry));
        }
        for (Spatial spatial : structuralChanges) {
            if (belongsToRoot(spatial)) {
                collect(spatial);
                if (!indexOwnedSubtrees(spatial)) {
                    for (Spatial parent = spatial.getParent(); parent != null; parent = parent.getParent()) {
                        ownedSubtrees.remove(parent);
                    }
                }
            }
        }
        for (Batch previous : changedBatches) {
            int position = batches.indexOf(previous);
            previous.release();
            if (previous.instances.isEmpty()) {
                batches.remove(position);
                continue;
            }
            Batch replacement = new Batch(previous.instances.get(0), previous.occluder, previous.queryOcclusion);
            for (Geometry geometry : previous.instances) {
                replacement.indices.put(geometry, replacement.instances.size());
                replacement.instances.add(geometry);
                owners.put(geometry, replacement);
            }
            batches.set(position, replacement);
        }
        // Re-evaluate only ancestor paths. Static descendant subtrees retain their
        // ownership cache; no full-world registration scan is performed.
        List<Spatial> ancestors = new ArrayList<>(ownershipChanges);
        ancestors.sort((a, b) -> Integer.compare(depth(b), depth(a)));
        for (Spatial ancestor : ancestors) {
            if (!belongsToRoot(ancestor)) {
                continue;
            }
            boolean owned = ancestor.getNumControls() == 0;
            if (ancestor instanceof Node) {
                for (Spatial child : ((Node) ancestor).getChildren()) {
                    if (!ownedSubtrees.contains(child)) {
                        owned = false;
                        break;
                    }
                }
            } else {
                owned &= handled.contains(ancestor);
            }
            if (owned) {
                ownedSubtrees.add(ancestor);
            }
        }
        ownershipChanges.clear();
        structuralChanges.clear();
        changedBatches.clear();
        // Forget unloaded material objects without rescanning the resident scene.
        canonicalMaterials.clear();
        materialRepresentatives.clear();
    }

    private void submitQueue(RenderQueue queue) {
        GeometryList opaque = queue.getOpaqueQueue();
        List<Geometry> fallback = new ArrayList<>();
        for (int i = 0; i < opaque.size(); i++) {
            if (!handled.contains(opaque.get(i))) {
                fallback.add(opaque.get(i));
            }
        }
        opaque.clear();
        for (Batch batch : batches) {
            for (int draw = 0; draw < batch.drawPacketCount(); draw++) {
                opaque.add(batch.proxies.get(draw));
            }
        }
        for (Geometry geometry : fallback) {
            opaque.add(geometry);
        }
    }

    private void buildHiZ() {
        Renderer renderer = manager.getRenderer();
        Camera camera = viewport.getCamera();
        if (hiz == null || hiz.width != camera.getWidth() || hiz.height != camera.getHeight()) {
            if (hiz != null) {
                hiz.close();
            }
            hiz = new HiZPyramid(assets, camera.getWidth(), camera.getHeight());
        }
        com.jme3.texture.FrameBuffer output = renderer.getCurrentFrameBuffer();
        String previousTechnique = manager.getForcedTechnique();
        com.jme3.material.Material previousMaterial = manager.getForcedMaterial();
        try {
            if (hizDiagnostics != null) {
                hizDiagnostics.start(0);
            }
            renderer.setFrameBuffer(hiz.targets.get(0));
            renderer.setViewPort(0, 0, camera.getWidth(), camera.getHeight());
            renderer.clearBuffers(false, true, false);
            manager.setForcedMaterial(null);
            manager.setForcedTechnique("PreShadow");
            for (Batch batch : batches) {
                if (batch.occluder && batch.proxy.getMaterial().getMaterialDef().getTechniqueDefs("PreShadow") != null) {
                    for (int draw = 0; draw < batch.drawPacketCount(); draw++) {
                        manager.renderGeometry(batch.proxies.get(draw));
                    }
                }
            }
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedMaterial(previousMaterial);
            if (hizDiagnostics != null) {
                hizDiagnostics.stop();
                hizDiagnostics.start(1);
            }
            hiz.reduce(manager);
        } finally {
            if (hizDiagnostics != null) {
                hizDiagnostics.stop();
            }
            manager.setForcedTechnique(previousTechnique);
            manager.setForcedMaterial(previousMaterial);
            renderer.setFrameBuffer(output);
            camera.setViewPort(camera.getViewPortLeft(), camera.getViewPortRight(),
                    camera.getViewPortBottom(), camera.getViewPortTop());
            manager.setCamera(camera, false);
        }
    }

    private boolean sameLights(Geometry a, Geometry b) {
        if (a.getWorldLightList().size() != b.getWorldLightList().size()) {
            return false;
        }
        for (int i = 0; i < a.getWorldLightList().size(); i++) {
            if (a.getWorldLightList().get(i) != b.getWorldLightList().get(i)) {
                return false;
            }
        }
        return true;
    }

    private void markDirty(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Batch batch = owners.get(spatial);
            if (batch != null) {
                batch.dirty.set(batch.indices.get(spatial));
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                markDirty(child);
            }
        }
    }

    private void enqueueDirty(Spatial spatial) {
        // A parent transform setter also invalidates every descendant. Queue the
        // subtree once; do not recursively rediscover it for every child event.
        for (Spatial ancestor = spatial; ancestor != null; ancestor = ancestor.getParent()) {
            if (dirtySpatials.contains(ancestor)) {
                return;
            }
            if (ancestor == root) {
                break;
            }
        }
        dirtySpatials.add(spatial);
    }

    private boolean indexOwnedSubtrees(Spatial spatial) {
        boolean owned = spatial.getNumControls() == 0;
        if (spatial instanceof Geometry) {
            owned &= handled.contains(spatial);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                // Visit every child even after finding a fallback branch.
                boolean childOwned = indexOwnedSubtrees(child);
                owned &= childOwned;
            }
        } else {
            owned = false;
        }
        if (owned) {
            ownedSubtrees.add(spatial);
        }
        return owned;
    }

    // Check mesh deformation data, not only late-bound material animation uniforms.
    // Skeletons, morphs and transparency deliberately retain the traditional path.
    static boolean supportsGeometry(Geometry geometry) {
        return !(geometry.isGrouped()
                    || geometry instanceof com.jme3.scene.instancing.InstancedGeometry
                    || geometry.getMaterial() == null || geometry.getMaterial().isTransparent()
                    || geometry.getQueueBucket() != RenderQueue.Bucket.Opaque
                    || geometry.getCullHint() == Spatial.CullHint.Always
                    || geometry.getBatchHint() == Spatial.BatchHint.Never
                    || geometry.getWorldBound() == null
                    || geometry.getMesh().isAnimated()
                    || geometry.getMesh().getMorphTargets().length > 0
                    || geometry.getMaterial().getAdditionalRenderState().getBlendMode() != com.jme3.material.RenderState.BlendMode.Off
                    || geometry.getMesh().getMode() != Mesh.Mode.Triangles
                    || geometry.getMesh().getBuffer(VertexBuffer.Type.Index) == null
                    || geometry.getMaterial().getParam("NumberOfBones") != null
                    || geometry.getMaterial().getParam("NumberOfMorphTargets") != null
                    || geometry.getMaterial().getMaterialDef().getMaterialParam("UseInstancing") == null
                    || !geometry.getWorldMatParamOverrides().isEmpty());
    }

    private void collect(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            if (owners.containsKey(geometry)) {
                return;
            }
            watch(geometry);
            if (!supportsGeometry(geometry)) {
                return;
            }
            boolean writesOcclusion = occluderFilter.test(geometry);
            boolean queriesOcclusion = occlusionQueryFilter.test(geometry);
            Batch found = null;
            for (Batch batch : batches) {
                if (canonical(batch.source.getMaterial()) == canonical(geometry.getMaterial())
                        && batch.instances.size() < 65536
                        && (batch.source.getMesh() == geometry.getMesh()
                            || (multiDrawIndirect && meshMerging && MeshDrawPlan.compatible(batch.source.getMesh(), geometry.getMesh())))
                        && batch.occluder == writesOcclusion
                        && batch.queryOcclusion == queriesOcclusion
                        && sameLights(batch.source, geometry)) {
                    found = batch;
                    break;
                }
            }
            if (found == null) {
                found = new Batch(geometry, writesOcclusion, queriesOcclusion);
                batches.add(found);
            }
            if (owners.containsKey(geometry)) {
                return;
            }
            if (found.plan != null) {
                changedBatches.add(found);
            }
            found.indices.put(geometry, found.instances.size());
            found.instances.add(geometry);
            owners.put(geometry, found);
            handled.add(geometry);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                collect(child);
            }
        }
    }

    private void clear() {
        historyValid = false;
        frozenSnapshot = false;
        frozenCamera = null;
        if (hizDiagnostics != null) {
            hizDiagnostics.close();
            hizDiagnostics = null;
        }
        if (hiz != null) {
            hiz.close();
            hiz = null;
        }
        for (Batch batch : batches) {
            batch.release();
        }
        batches.clear();
        ownershipChanges.clear();
        structuralChanges.clear();
        changedBatches.clear();
        canonicalMaterials.clear();
        materialRepresentatives.clear();
        materialWatches.clear();
        watchedGeometries.clear();
        handled.clear();
        owners.clear();
        ownedSubtrees.clear();
        dirtySpatials.clear();
        rebuild = true;
    }

    @Override
    public void cleanup() {
        if (ownsUpdateIndex) {
            ((Node) root).setIncrementalUpdatesEnabled(false);
            ownsUpdateIndex = false;
        }
        root.removeSceneChangeListener(sceneListener);
        clear();
        shading.cleanup();
        manager = null;
        viewport = null;
    }

    private final class Batch {
        final Geometry source;
        MeshDrawPlan plan;

        int drawPacketCount() {
            return multiDrawIndirect ? 1 : proxies.size();
        }

        final boolean occluder;
        final boolean queryOcclusion;
        final List<Geometry> instances = new ArrayList<>();
        final Map<Geometry, Integer> indices = new IdentityHashMap<>();
        final BitSet dirty = new BitSet();
        final BufferObject data = buffer(0);
        final BufferObject visible = buffer(1);
        final BufferObject commands = buffer(2);
        final BufferObject updates = buffer(3);
        final BufferObject drawMetadata = buffer(4);
        final BufferObject instanceDraws = buffer(5);
        final BufferObject compacted = buffer(6);
        final BufferObject drawCount = buffer(7);
        Material compact;


        final Material cull = new Material(assets, "Common/MatDefs/GpuScene/Cull.j3md");
        final Material update = new Material(assets, "Common/MatDefs/GpuScene/Update.j3md");
        GpuSceneGeometry proxy;
        final List<GpuSceneGeometry> proxies = new ArrayList<>();
        final Vector3f extent = new Vector3f();
        float[] records;
        float[] updateRecords;

        Batch(Geometry source, boolean occluder, boolean queryOcclusion) {
            this.source = source;
            this.occluder = occluder;
            this.queryOcclusion = queryOcclusion;
        }

        void release() {
            data.dispose();
            visible.dispose();
            commands.dispose();
            updates.dispose();
            drawMetadata.dispose();
            instanceDraws.dispose();
            compacted.dispose();
            drawCount.dispose();
            if (plan != null) {
                plan.close();
            }
        }

        private void initializeDraws() {
            batchBuildCount++;
            plan = new MeshDrawPlan(instances, lodThresholds.length + 1, multiDrawIndirect);
            data.declareField("records", VarType.FloatArray);
            visible.declareField("indices", VarType.IntArray);
            commands.declareField("command", VarType.IntArray);
            commands.setFieldValue("command", new int[plan.sources.size() * 5]);
            updates.declareField("updates", VarType.FloatArray);
            drawMetadata.declareField("draws", VarType.IntArray);
            instanceDraws.declareField("instances", VarType.IntArray);
            drawMetadata.setFieldValue("draws", plan.drawData);
            instanceDraws.setFieldValue("instances", plan.instanceData);
            for (int draw = 0; draw < plan.sources.size(); draw++) {
                GpuSceneGeometry geometry = new GpuSceneGeometry(plan.sources.get(draw), commands, visible, plan.lods.get(draw), draw);
                geometry.getMaterial().setBindlessTextures(bindlessTextures);
                proxies.add(geometry);
            }
            proxy = proxies.get(0);
            if (multiDrawIndirect) {
                proxy.enableMultiDraw(plan.packed, proxies.size(), plan.vertexCounts);
                if (commandCompaction && manager.getRenderer().getCaps().contains(Caps.IndirectDrawCount)) {
                    compact = new Material(assets, "Common/MatDefs/GpuScene/Compact.j3md");
                    compacted.declareField("commands", VarType.IntArray);
                    compacted.setFieldValue("commands", new int[proxies.size() * 5]);
                    drawCount.declareField("count", VarType.IntArray);
                    drawCount.setFieldValue("count", new int[1]);
                    compact.setShaderStorageBufferObject("Commands", commands);
                    compact.setShaderStorageBufferObject("Compacted", compacted);
                    compact.setShaderStorageBufferObject("DrawCount", drawCount);
                    compact.setInt("Capacity", proxies.size());
                    proxy.enableCompaction(compacted, drawCount);
                }
            }
            cull.setInt("DrawCount", proxies.size());
            cull.setParam("LodThresholds", VarType.FloatArray, Arrays.copyOf(lodThresholds, 8));
            cull.setShaderStorageBufferObject("Instances", data);
            cull.setShaderStorageBufferObject("Visible", visible);
            cull.setShaderStorageBufferObject("Commands", commands);
            cull.setShaderStorageBufferObject("DrawMetadata", drawMetadata);
            cull.setShaderStorageBufferObject("InstanceDraws", instanceDraws);
            update.setShaderStorageBufferObject("Instances", data);
            update.setShaderStorageBufferObject("Updates", updates);
        }

        void prepare(boolean useHiZ) {
            prepare(useHiZ, viewport.getCamera().getViewProjectionMatrix());
        }

        void prepare(boolean useHiZ, Matrix4f occlusionViewProjection) {
            if (plan == null) {
                initializeDraws();
            }
            int count = instances.size();
            boolean initial = records == null;
            if (initial) {
                if (count > 16777216) {
                    throw new IllegalStateException("Instance batch exceeds exact update-index range");
                }
                records = new float[count * 20];
                visible.setFieldValue("indices", new int[Math.multiplyExact(plan.visibleCapacity, 16)]);
                dirty.set(0, count);
            }
            boolean changed = !dirty.isEmpty();
            for (int i = dirty.nextSetBit(0); i >= 0; i = dirty.nextSetBit(i + 1)) {
                Geometry geometry = instances.get(i);
                Matrix4f world = geometry.getWorldMatrix();
                for (int column = 0; column < 4; column++) {
                    for (int row = 0; row < 4; row++) {
                        records[i * 20 + column * 4 + row] = world.get(row, column);
                    }
                }
                com.jme3.bounding.BoundingVolume bound = geometry.getWorldBound();
                Vector3f center = bound.getCenter();
                records[i * 20 + 16] = center.x;
                records[i * 20 + 17] = center.y;
                records[i * 20 + 18] = center.z;
                records[i * 20 + 19] = bound instanceof BoundingBox
                        ? ((BoundingBox) bound).getExtent(extent).length() : ((BoundingSphere) bound).getRadius();
                if (!initial) {
                    proxy.expandBound(bound);
                }
            }
            if (changed) {
                if (initial) {
                    data.setFieldValue("records", records);
                    proxy.refresh(instances);
                    for (int level = 1; level < proxies.size(); level++) {
                        proxies.get(level).shareBoundsAndLights(proxy);
                    }
                } else {
                    int updateCount = dirty.cardinality();
                    if (updateRecords == null || updateRecords.length != updateCount * 21) {
                        updateRecords = new float[updateCount * 21];
                    }
                    int target = 0;
                    for (int i = dirty.nextSetBit(0); i >= 0; i = dirty.nextSetBit(i + 1)) {
                        updateRecords[target] = i;
                        System.arraycopy(records, i * 20, updateRecords, target + 1, 20);
                        target += 21;
                    }
                    updates.setFieldValue("updates", updateRecords);
                    update.setInt("UpdateCount", updateCount);
                    update.dispatchCompute(manager, (updateCount + 63) / 64, 1, 1);
                }
                dirty.clear();
            }
            Camera camera = viewport.getCamera();
            cull.setMatrix4("ViewProjection", camera.getViewProjectionMatrix());
            cull.setMatrix4("OcclusionViewProjection", occlusionViewProjection);
            cull.setMatrix4("View", camera.getViewMatrix());
            cull.setVector3("CameraPosition", camera.getLocation());
            cull.setFloat("MaxDistance", maxDistance);
            cull.setFloat("MinPixels", minPixels);
            cull.setBoolean("Orthographic", camera.isParallelProjection());
            cull.setFloat("ProjectionScale", camera.getHeight() * Math.abs(camera.getProjectionMatrix().m11) * .5f);
            cull.setInt("Count", count);
            cull.setBoolean("Frustum", frustum);
            cull.setBoolean("Occlusion", useHiZ && queryOcclusion);
            if (useHiZ) {
                hiz.bind(cull);
            }
            cull.setBoolean("Reset", true);
            cull.dispatchCompute(manager, (proxies.size() + 63) / 64, 1, 1);
            cull.setBoolean("Reset", false);
            cull.dispatchCompute(manager, (count + 63) / 64, 1, 1);
            if (compact != null) {
                compact.setBoolean("Reset", true);
                compact.dispatchCompute(manager, 1, 1, 1);
                compact.setBoolean("Reset", false);
                compact.dispatchCompute(manager, (proxies.size() + 63) / 64, 1, 1);
            }
            // SSBO output is consumed both as indirect commands and vertex attributes.
            manager.getRenderer().memoryBarrier(0x2041);
        }
    }

    private static BufferObject buffer(int binding) {
        return new BufferObject(binding, BufferObject.BufferType.ShaderStorageBufferObject);
    }
}
