/**
 * General rigid-geometry GPU submission for the existing JME material system.
 * LegacyApplication requests GPU Scene and Bindless by default through AppSettings.
 * Compute requires OpenGL 4.3; Bindless and indirect-count submission have separate
 * capability checks. Unsupported features retain ordinary bindings/submissions.
 * Skeletal meshes, Morph, transparency, legacy InstancedGeometry/grouped geometry,
 * explicit BatchHint.Never and materials without the UseInstancing contract stay
 * on their traditional paths. No asset-specific rendering logic exists here.
 *
 * <p>Automatic policy preserves RenderPath selection and explicit custom-pipeline
 * ownership. It accelerates main views, not shadow-map traversal or GUI views.
 * Original geometry still supplies shadows; main-view Hi-Z never rejects shadow
 * casters. Automatic policy enables MDI, compatible mesh/material merging and
 * Bindless. It does not silently enable Hi-Z, distance/screen cutoffs, change LOD
 * quality, or enable command compaction (whose compute cost can exceed its gain).
 * All scene/configuration edits must occur on the application/render thread.</p>
 * <pre>{@code
 * // 第一步：普通应用无需手工安装管线；需要兼容旧项目时在启动前关闭。
 * AppSettings settings = new AppSettings(true);
 * settings.setGpuSceneEnabled(false);
 * settings.setGpuSceneBindlessEnabled(false);
 * app.setSettings(settings);
 * // 第二步：simpleInitApp/simpleUpdate 内可分别开启，源材质照常使用 j3md。
 * renderManager.setGpuSceneEnabled(true);
 * renderManager.setGpuSceneBindlessEnabled(true);
 * // 第三步：需要精细剔除策略时，显式安装独立装饰器，覆盖自动策略。
 * RenderPipeline shading = new DeferredPipeline(assetManager,
 *         renderManager.getDeferredShadingModels());
 * GpuScenePipeline pipeline = new GpuScenePipeline(assetManager, world, shading);
 * pipeline.setEnabled(true);
 * pipeline.setMultiDrawIndirect(true);
 * pipeline.setMeshMerging(true);
 * pipeline.setMaterialMerging(true);
 * pipeline.setBindlessTextures(true);
 * pipeline.setMaxDistance(600);
 * pipeline.setMinPixels(2);
 * pipeline.setLodThresholds(80, 30, 10, 3);
 * renderManager.setPipeline(viewPort, pipeline);
 * // 第四步：明确遮挡体/查询体，再启用主视图历史两阶段遮挡。
 * pipeline.setOccluderFilter(geometry -> Boolean.TRUE.equals(geometry.getUserData("Occluder")));
 * pipeline.setOcclusionQueryFilter(geometry -> !Boolean.TRUE.equals(geometry.getUserData("QueryExempt")));
 * pipeline.setHiZMode(HiZMode.HISTORY_TWO_PHASE);
 * pipeline.setOcclusionCulling(true);
 * pipeline.setHiZDiagnosticsEnabled(true);
 * // 第五步：场景块挂载/卸载自动更新，仅受影响批次重建，其他批次保持驻留。
 * world.attachChild(loadedChunk);
 * world.detachChild(unloadedChunk);
 * loadedChunk.move(1, 0, 0);
 * // 第六步：材质/网格替换 setter 自动通知；直接修改顶点数据需显式刷新。
 * pipeline.refreshSubtree(editedMesh);
 * // 第七步：大量空命令时可试用 GPU 压缩；短列表应实际测量再决定。
 * pipeline.setCommandCompaction(true);
 * // 第八步：冻结可见集后移动相机检查结果，然后恢复剔除。
 * pipeline.setCullingFrozen(true);
 * pipeline.setCullingFrozen(false);
 * // 第九步：删除显式覆盖恢复自动策略；关闭自动策略则恢复传统提交。
 * renderManager.setPipeline(viewPort, null);
 * renderManager.setGpuSceneEnabled(false);
 * }</pre>
 *
 * <p>Material merging means equivalent definitions, techniques, parameter values
 * and render states. Different textures/colors remain separate batches; Bindless
 * alone does not make arbitrary materials share one shader invocation. Mesh
 * packing preserves compatible vertex layouts and source asset ownership.
 * Structural updates rebuild affected batches of at most 65,536 geometries;
 * this is batch-granular residency, not virtual geometry or asynchronous asset IO.
 * Applications choose their world-chunk loading policy and enqueue attach/detach.
 * History Hi-Z conservatively selects occluders, renders their current depth,
 * then validates all current candidates; it is not stale final visibility reuse.</p>
 *
 * <p>Runnable examples in jme3test.gpuDrivenRendering: TestBindlessRendering,
 * TestMultiDrawIndirect, TestMaterialMerging, TestCommandCompaction (300,000 models);
 * TestGpuSceneStreaming (100,000 models); TestHiZOcclusion; TestGpuSceneSettings.
 * Gradle verification: gpuBindlessCheck, gpuMeshMergeCheck, gpuMaterialMergeCheck,
 * gpuCompactCheck, gpuCompactFallbackCheck, gpuStreamingCheck, gpuHiZCompactSmoke,
 * gpuSettingsCheck. Add -PgpuDeferred to the 300k feature checks for Deferred.
 * Separate backend checks: gpuSettingsLwjgl3Check, gpuHiZLwjgl3Check,
 * gpuCombinedLwjgl3Check. Timed benchmarks exclude synchronous diagnostic reads;
 * compare images and visible workloads, not FPS from unrelated camera views.</p>
 */
package com.jme3.renderer.gpuscene;
