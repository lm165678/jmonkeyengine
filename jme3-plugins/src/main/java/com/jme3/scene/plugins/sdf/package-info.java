/**
 * Offline triangle distance-field generation and j3o-persisted model data.
 *
 * <p>Geometry is captured in root-local coordinates, then baked without a renderer.
 * Grid values are float Euclidean distances to the nearest captured triangle;
 * sign is optional and requires suitable closed, non-self-intersecting surfaces.
 * This is not mesh repair, CSG union, voxel occupancy, an animation baker, or DDGI.
 * References: <a href="https://www.pbr-book.org/4ed/Primitives_and_Intersection_Acceleration/Bounding_Volume_Hierarchies">BVH acceleration</a>
 * and <a href="https://doc.cgal.org/latest/Polygon_mesh_processing/">closed-mesh side classification</a>.
 * No code or runtime dependency from either reference is included.</p>
 *
 * <pre>{@code
 * // 第一步：编辑器加载离线模型，或在场景线程上捕获正在编辑的模型快照。
 * Spatial model = assetManager.loadModel("Models/Sponza/Sponza.j3o");
 * SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(model);
 * // 第二步：Sponza 含开放表面，默认选择无符号距离供后续 DDGI 表面距离查询。
 * SdfSettings settings = new SdfSettings(64, 32, 64, .05f, SdfSettings.SignMode.UNSIGNED);
 * // 第三步：后台生成；进度回调转发 UI 时使用编辑器自己的线程调度机制。
 * SdfVolume sdf = SdfGenerator.generate(snapshot, settings, progress -> true);
 * // 第四步：成功且源模型未改变时，回到场景线程挂载。生成器本身不修改模型。
 * sdf.attachTo(model);
 * // 第五步：用 BinaryExporter 保存到新的路径，不覆盖输入的 Sponza.j3o。
 * // 完整的 CREATE_NEW、重载验证及切片导出见 jme3test.sdf.TestSponzaSdf。
 * // 第六步：运行时加载同样的 j3o，然后按需转换 3D texture。
 * SdfVolume restored = loadedModel.getUserData(SdfVolume.USER_DATA_KEY);
 * Texture3D texture = restored.toTexture3D();
 * // 后续 DDGI shader 必须理解 root-local bounds、采样中心映射和插值安全余量。
 * // 保守步长为 max(abs(sample)-margin,0)；0 不是精确命中，需要细化或命中策略。
 * }</pre>
 *
 * <p>Changing geometry or descendant transforms invalidates a bake. Moving the root
 * does not alter local samples, but distance-to-world conversion must account for
 * scale. Only CPU Position buffers are captured; GPU deformation, alpha-tested
 * holes and material displacement are not part of the geometry snapshot. Keep
 * jme3-plugins on the runtime classpath when loading models with SdfVolume data.</p>
 *
 * <p>SDK baking has independent SDF and surface-attribute entry points. Either
 * result can be stored alone. SurfaceBakeData contains six-direction layout/mapping,
 * actual diffuse reflectance, local shading normal, emission and depth pixels,
 * and dependency signatures. These are not lit radiance. SdfBakeData remains a
 * legacy CPU-only SDF/layout bundle; new callers should use the independent APIs.
 * GlobalDistanceField provides CPU incremental clipmap composition; DDGI probe
 * updates and production GPU composition are not implemented. SurfaceAttributeAtlas
 * provides bounded whole-card residency, not virtual-texture paging.</p>
 * <pre>{@code
 * // 第一步：SDK 按需独立调用；SDF 只处理几何，不隐式生成表面布局或属性。
 * SdfVolume sdf = SdfGenerator.bake(source, settings, progress);
 * // 第二步：渲染线程视口边界同步捕获全部属性；需要 OpenGL 3.3 和有效的 restoreView。
 * // source 不得被编辑器或动画同时修改。默认 CPU 像素预算 256 MiB，可用重载指定预算。
 * SurfaceBakeData surfaces = SurfaceAttributeBaker.bake(
 *     source, dependencyContentHash, renderManager, restoreView, 128, progress);
 * // 第三步：成功后挂载；任意一个调用失败/取消，都不会自动覆盖模型原有数据。
 * sdf.attachTo(source);
 * surfaces.attachTo(source);
 * // 第四步：BinaryExporter 另存新 j3o；仅需要 SDF 时省略第二步和 surfaces.attachTo。
 * // 第五步：重载后分别读取两个 userdata；属性像素直接上传，无需重捕获。
 * SurfaceBakeData saved = loadedModel.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * if (saved != null && saved.validate(loadedModel, dependencyContentHash) == SurfaceBakeData.Status.VALID) {
 *     SurfaceAttributeData.Textures textures = saved.getAttributes().uploadAll();
 *     // 接入预览/查询；消费者移除后在渲染线程 textures.close()，CPU 数据仍然保留。
 * }
 * // 第六步：两者都有时 isCompatibleWith(sdf) 校验几何来源，再用命中点查询布局与属性。
 * // 修改材质/外部贴图后重烘焙属性；只改灯光无需重烘焙。几何改变则重烘焙两者。
 * }</pre>
 * <p>Six-direction cards do not guarantee concave-surface coverage. Callers must
 * validate projected depth, report coverage misses, and never choose the first
 * broad-phase material candidate unconditionally. Attribute alpha discard can
 * reject a hole, while geometric SDF baking still includes that triangle: a later
 * ray query must handle that discrepancy explicitly. Normals are geometry-local;
 * world shading needs the instance inverse-transpose normal transform. Card pixels
 * are nearest-filtered to avoid blending unrelated surface depths/materials.
 * This baseline excludes skinned/morphed/instanced/transparent-blended capture,
 * automatic coverage partitioning and lit surface radiance caches.</p>
 *
 * <p>Global distance-field instances may share immutable local SdfVolume data and
 * SurfaceBakeData when their geometry/material inputs match. Their transforms are
 * distinct. GlobalDistanceField discovers baked roots and handles rigid instance
 * motion; it does not upload SurfaceBakeData. SurfaceAttributeAtlas shares identical
 * SurfaceBakeData objects and retains instance-specific projection transforms.
 * Independently loaded copies are not automatically deduplicated by content hash.
 * Lit radiance generally needs separate instance-dependent storage. The distance
 * union alone does not identify the hit instance: later attribute queries need an
 * instance spatial index/refinement and the existing card depth/coverage checks.</p>
 * <pre>{@code
 * // 第一步：模型已加载并设置好实例变换，创建固定显存预算的世界距离缓存。
 * GlobalDistanceField global = new GlobalDistanceField(64, 3, 0.5f);
 * // 第二步：每帧世界变换更新后调用，不会再次烘焙，也不会上传任何表面属性纹理。
 * rootNode.updateGeometricState();
 * global.update(rootNode, cam.getLocation());
 * // 第三步：只将三级全局纹理交给查询/预览 shader；每个实例无需单独绑定 SDF sampler。
 * // 完整三实例、旋转、非均匀缩放、移除/恢复和空格切换见 TestGlobalDistanceField。
 * // 第四步：移除消费者后 global.close()。仅有 SurfaceBakeData、没有 SDF 的模型会跳过合并。
 * }</pre>
 * <pre>{@code
 * // 第一步：创建按相机距离管理的属性图集；与 GlobalDistanceField 独立，允许没有 SDF。
 * SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(512, 22f, 3);
 * // 第二步：每帧世界变换更新后调度；不同尺寸卡片按原分辨率装入固定容量纹理。
 * // 图集首次建立 SurfaceSceneIndex；后续场景 setter/attach/detach/userdata 通知自动维护。
 * // 静态场景不全量遍历；相机通过空间网格查附近候选，预算与缺失回退行为不变。
 * atlas.setUploadByteBudget(256 * 1024);
 * atlas.setRetentionMargin(2f);
 * atlas.update(rootNode, cam.getLocation(), renderer);
 * // 第三步：只绑定四张共享纹理；缺失/超预算的卡片必须回退，不采样旧槽位。
 * SurfaceAttributeAtlas.Region region = atlas.getRegion(model, 4);
 * if (region != null) {
 *     Vector4f rect = region.getUvTransform();
 *     Matrix4f worldToCard = atlas.getWorldToCard(model, 4);
 *     // Common/ShaderLib/SurfaceAtlasLookup.glsllib 按 rect 映射 UV 并校验深度。
 * }
 * // 第四步：移除消费者后 atlas.close()；详细多尺寸/淘汰案例见 TestSurfaceAttributeAtlas。
 * }</pre>
 */
package com.jme3.scene.plugins.sdf;
