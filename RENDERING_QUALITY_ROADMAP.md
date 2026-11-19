# 渲染质量升级路线与跨机器开发交接

更新日期：2026-11-16。本文是开发方案，不是已完成功能列表。

## 目标与当前状态

用户希望将当前 JME 分支的渲染质量提升到适合商业游戏的水平，重点改善光照、Bloom、AO、Tonemap 和运动时的画面稳定性。参考 UE5 的整体渲染设计，但保持 JME 材质系统、Forward/Deferred 路径和旧项目兼容。不要将通用引擎功能写成仅适用于一个示例的实现。

2026-11-17：已新增独立手动曝光 ExposureFilter（默认 0 EV），并接入 SDK 主场景预览，位于 Bloom/ToneMap 前。GPU 浮点像素、alpha、旁路恢复与序列化测试通过；MSAA 追加验证受本机进程崩溃阻塞。完整色彩一致性审计、统一输出、自动曝光和后续阶段仍是待办，不能把这项局部实现视为阶段一全部完成。

引擎基线：`42353672aa5f2b3d64b021c56426afbcc1aed068`，分支 `dev_feature_lightprobe`，已核实推送到 `https://github.com/lm165678/jmonkeyengine`。该提交修复自动 GI/物理发现与无投影物体时阴影遍历的性能回退，包含服务禁用时释放缓存的修复。SDK 本轮不涉及。

已观察到的源码情况：

- `jme3-effects/src/main/resources/Common/MatDefs/Post/bloomExtract.frag`：传统 Bloom 提取使用亮度硬阈值和幂运算，存在阈值附近不连续的风险；具体视觉瑕疵需要固定场景复现。
- `jme3-effects/src/main/resources/Common/MatDefs/Post/ToneMap.frag`：现有 ToneMapFilter 使用固定 Filmic 曲线。
- 已有 `SoftBloomFilter`、`KHRToneMapFilter`，应先审查、比较和复用可用部分，不假设仓库只有最旧算法。
- 已有 FrameGraph、GPU Scene、模型 SDF/表面缓存与 Probe Volume 等基础；具体接口和完整度应读取当前源码确认，不从历史聊天推断。
- `automaticSceneServicesSmoke` 存在区域混合断言失败：`[102,0] / [1,101] / [0,102]`。修改前后均复现，属于已有问题，不能宣称 GI 回归全部通过。详见 `DEV_HANDOFF.md`。

## 总体设计原则

1. 先统一颜色和亮度的数据约定，再替换算法。Forward、Deferred、场景捕获和最终显示必须保持一致。
2. GPU Scene 负责剔除和提交，不决定材质光照模型、GI 或后处理算法。
3. 公共效果通过 FrameGraph 声明资源、依赖和生命周期，各渲染路径提供所需输入。沿用主视口图，避免效果各自维护不可观察的执行图。
4. 材质拥有 shader 声明；GBuffer 名称保持中性，各光照模型负责自己的编码和解码。
5. 新质量管线提供整体预设和独立开关，保留旧项目的原有画面。默认值调整必须明确迁移影响，不直接覆盖旧 Filter 行为。
6. 新 API/配置必须在源码注释中包含完整、分步骤的中文用法，说明默认值、能力回退、运行时切换、恢复、线程和释放要求；不能只写 API 描述或测试。
7. 每个 feature 都有独立、可运行的 jme3-examples 示例和自动验证入口，按功能分类目录，不把所有案例堆到 renderpath。
8. 普通场景不得因启用新服务恢复为每帧整树发现；记录 CPU、GPU、内存和上传量，特别检查十万对象场景。

## 分阶段实施

### 阶段一：HDR、曝光、Tonemap 和显示输出

这是推荐的首个开发阶段，先做审计和基准，再确定具体公共 API。

- 审计颜色贴图 sRGB 解码，法线/粗糙度/金属度等数据贴图的线性读取，以及渲染目标格式。
- 光照、发光和 Bloom 保持在线性 HDR 空间；避免提前钳制高亮值。
- 明确 Tonemap 与显示编码的边界，各执行一次；检查重复 Gamma、场景捕获被二次处理等问题。
- 先提供可重复的手动曝光，再加入亮度直方图、自动曝光上下限、曝光补偿、测光与适应速度。
- 将 Tonemap 曲线选择、曝光和艺术调色分开。仅替换为一个 ACES 近似公式不等于完整的 UE 色彩管线。
- 先确保 SDR 输出可靠，HDR 显示输出作为后续明确目标，不默认宣称已经支持。
- 预曝光不是简单乘一个系数：若采用，必须统一历史帧、Bloom、反射、GI 和捕获的数据尺度，并提供调试视图。

验收：相同材质、光照和曝光下，对比 Forward/Deferred 与场景捕获；检查灰阶、饱和高亮、黑位、亮部渐变、亮度连续性和重复编码。原有低质量/兼容路径应可恢复。

### 阶段二：公共时序能力

2026-11-18 用户明确安排：TAA 留到后续独立开发，本轮后处理升级不实现 TAA。
以下是前置依赖与验收待办，不是已实现功能：

- [ ] 定义统一 Velocity 契约：屏幕空间单位、方向、是否包含 jitter，以及前后帧分辨率约定。
- [ ] 输出相机、静态物体、刚体实例的运动矢量，覆盖 Forward、Deferred、GPU Scene 与传统回退。
- [ ] 保留骨骼和 Morph 的上一帧形变数据；不能只用上一帧模型矩阵代替形变运动。
- [ ] 对透明、粒子、Alpha Test 和缺少上一帧数据的材质定义有效性或 reactive mask 策略。
- [ ] 接入相机 jitter、每视口历史、重投影与深度/法线拒绝，处理遮挡显露与屏幕边界。
- [ ] 统一曝光变化下历史颜色的尺度；相机切换、瞬移、resize、路径切换时重置历史。
- [ ] 实现历史裁剪、锐度控制和抗闪烁，测试细线、高光、快速运动及动画拖影；随后评估时序 AO。
- [ ] 增加 Velocity、历史权重、拒绝原因调试视图和独立可运行示例，记录 GPU 开销。

- 运动矢量：相机运动、刚体运动，以及不走 GPU Scene 的骨骼/Morph 等渲染路径都要有正确策略。
- 相机抖动、上一帧矩阵、每视口历史资源、重投影、深度/法线有效性与遮挡显露检测。
- 相机切换、瞬移、分辨率变化、视口变化、渲染路径切换时正确重置历史。
- 实现可用的 TAA，后续再考虑时序升采样；不能把简单历史混合称为成熟 TAA。
- 透明物体需要明确的时序处理/回退策略，不因它不走 GPU Scene 而忽略。
- 公共历史管理服务于 AO、反射、后续 DDGI，而非每个效果重复一套不同的历史规则。

验收：静止细线、高光闪烁、镜头平移、快速移动、遮挡显露、动画、透明物体、相机切换。静态截图通过不能替代运动测试。

### 阶段三：基础画质效果

**Bloom**：先评估 SoftBloomFilter；完善 HDR 精度、多尺度降采样/上采样、合成、亮点稳定性和可选平滑阈值。测试小亮点闪烁、边界漏光和不同分辨率的一致性。昂贵的卷积 Bloom 不必作为首版目标。

**AO**：评估 GTAO 路线，结合深度/法线引导滤波与时序稳定。明确它影响哪些间接光分量，避免把最终颜色整体乘黑；与 GI 遮蔽避免重复计暗。Forward 需要适当输入路径，不能只支持某一种 Deferred 材质。

**阴影**：完善级联稳定、偏移策略、接触细节和过滤。保留阴影灯数量/显存预算，提供调试信息。无投影物体时跳过工作，有投影物体时保持正确性。

**IBL**：核对环境贴图预滤波、BRDF 积分、粗糙度、材质能量与曝光约定；用统一测试材质球验证，而非通过 Bloom 掩盖错误。

验收：逐效果开关 A/B、Forward/Deferred 组合、动态稳定性、GPU Pass 耗时与资源占用。不得仅以“看起来更亮、更柔和”作为质量结论。

### 阶段四：间接光与反射

- 优先修复现有 Probe Volume 区域混合回归，验证漏光、边界、流送、运行时强度变化和资源释放。
- 漫反射 GI 和镜面反射分开规划。Probe Volume 不能替代金属、光滑表面需要的反射系统。
- 完善反射探针、视差修正/混合，再评估 SSR 与屏幕外回退；检查切换边界与时序稳定性。
- SDF DDGI 继续作为后续大功能：验证距离场命中、表面属性查询、直接光注入、更新预算与历史稳定性，再推进探针实时更新。
- SDF 和表面缓存只是基础，不等同于 Lumen。需对动态物体、缺失数据、超出驻留范围和不支持几何体定义明确行为。

验收：室内外过渡、遮挡物变化、动态灯、金属/粗糙表面、无缓存/缺失页、流送和多视口。不得以已有 SDF 可视化代替 GI 正确性验证。

### 阶段五：摄影效果

在前述基础正确且稳定后，再开发景深、运动模糊、体积雾、局部曝光等。每项单独定义质量档位、适用范围和性能预算，避免用摄影效果遮盖材质或光照错误。

## 画质基准与性能验收

建立三个固定基准场景：

1. 室内：窄窗、暗部、亮部、材质球、接触阴影，观察 GI 与曝光。
2. 室外：地形、植被或复杂模型、天空和远景，观察阴影、IBL、AO 与时序。
3. 强发光体：不同大小/颜色/亮度的发光物、细高光、移动相机，观察 Bloom、Tonemap 和闪烁。

使用真实模型资产，附固定相机、灯光、分辨率、曝光和运动轨迹。保存参数与可复现实验命令；画质对比先固定手动曝光，再单独测试自动曝光。使用的资源应能在另一台电脑获得，不依赖本机绝对路径。

分别记录 CPU 帧阶段、GPU 各 Pass 毫秒、显存/资源大小、历史重置和上传量。性能统计不通过每帧同步 GPU 回读制造额外瓶颈。不同效果不能只比较 FPS 而忽略分辨率、对象可见数、画质档位和采样设置。

参考 UE 时尽可能统一资产、相机、光照、曝光和分辨率；允许离线参考用于判断光照误差。UE 的默认成片效果不是单个算法的输出。

## 下一台电脑的接续步骤

```powershell
# 在引擎仓库执行；先确认并处理当地未提交修改。
git status
git switch dev_feature_lightprobe
git pull --ff-only origin dev_feature_lightprobe
```

1. 先读 `AGENTS.md`、`DEV_HANDOFF.md`、本文，再检查实际源码。
2. 使用当地有效的 JDK 25/Gradle 环境；不要改 UE Android 使用的系统 JDK。代理地址属于本机配置，不照抄开发机端口。
3. 先跑现有基准确认环境，记录 GPU/驱动、分辨率、配置和结果：

```powershell
./gradlew.bat :jme3-examples:gpuDrivenPerformanceCheck -PgpuInstances=100000 -PgpuMoving=1000
./gradlew.bat :jme3-examples:renderPathSmoke -PrenderPathExample=TestPBRTerrainRenderPath
./gradlew.bat :jme3-core:test --tests com.jme3.app.SceneServiceCatalogTest --tests com.jme3.shadow.ShadowManagerTest
```

4. `automaticSceneServicesSmoke` 当前有上述已知失败，可用于复现，不能列为已通过检查。
5. 从阶段一的源码审计和三类画质基准开始；先确认色彩/HDR 合同，再设计实现，不直接将所有旧 Filter 替换。
6. 每阶段更新本文的进度与 DEV_HANDOFF，区分完成、测试、已知缺陷和计划。需要 SDK 配合时再读 SDK 自己的交接文件，本轮不修改 SDK。

## 官方参考

- [UE 色彩管理与 Filmic Tonemapper](https://dev.epicgames.com/documentation/en-us/unreal-engine/color-grading-and-the-filmic-tonemapper-in-unreal-engine)：理解曝光、Bloom、调色与输出的协作，而非只复制曲线。
- [UE Temporal Upscalers](https://dev.epicgames.com/documentation/en-us/unreal-engine/temporal-upscalers-in-unreal-engine)：时序输入、曝光和调试约定。
- [UE Temporal Super Resolution](https://dev.epicgames.com/documentation/en-us/unreal-engine/temporal-super-resolution-in-unreal-engine)：理解时序质量与渲染分辨率、效果顺序之间的关系。
- [UE Lumen 技术细节](https://dev.epicgames.com/documentation/en-us/unreal-engine/lumen-technical-details-in-unreal-engine)：屏幕追踪、场景追踪和表面缓存是组合系统。

这些文档用作架构与算法参考，不表示 JME 已实现对应能力或保证达到相同性能。

## 待办：大世界表面卡片流送与性能（2026-11-17）
- [ ] 磁盘分块存储和异步读取：避免随单个 J3O 一次性加载全部卡片像素；支持取消、失败回退和资源版本校验。
- [ ] 有预算的 CPU 卡片缓存与淘汰，配合现有 GPU 图集驻留及上传预算，分别统计磁盘、CPU、GPU 用量。
- [ ] 跨实例/重复加载资产共享表面数据，明确版本、引用计数、失效与卸载规则。
- [ ] 距离相关精度与预取：普通移动、高速移动和传送时，优先补齐附近卡片；处理缺失回退和密集区域反复淘汰。
- [ ] 专项测试普通行走、高速飞行、传送及密集区域，记录帧耗时（含尾延迟）、CPU/GPU 内存、读取/上传字节、卡片缺失率和淘汰频率；覆盖大量独特资产和重复实例。不得以普通几何体压力测试替代卡片性能验证。
- 当前边界：已有整卡 GPU 驻留、上传限额和局部上传；完整磁盘分块流送、CPU 数据淘汰及上述规模性能尚未验证。此节仅记录计划，不表示已实现。
- 提醒：用户要求以后提醒优化；下次继续大世界、表面缓存/卡片或流送性能工作时主动提起。本会话无可用自动提醒工具，未设置定时通知。

## 待办：Probe Volume 硬黑带根因与方向细节优化（2026-11-18）

2026-11-18 后续：紧凑八探针稳定模式修正可见性反复幂次压低导致的暗平台，改用连续线性置信度；仍为每探针四采样。33步 GPU 回归在旧版失败、新版四路径/两材质通过，耗时约1.680ms，TestScene3主视角/墙脚/背面保持平滑。解析球谐积分测试通过；当前方向低通后负值仅约1e-9量级，不能将剩余硬块都归因于SH截零。实际场景拱门阶梯明显减轻，但低能量暗区和完整SDK验收仍未解决，详见交接文档。

2026-11-18 更新：十六采样距离过滤被用户否决并撤回；它将同视角 GPU 时间由约 1.656ms 提升到 2.558ms，却仍有黑块。默认恢复八探针、每探针四采样，回退测得 1.679ms；TestScene3 对照图与原基线完全一致。圈内墙面即使旁路可见性、使用纯三线性/L0 插值仍有暗区，八个邻居均有效但烘焙能量明显不同，尚不能认定其正确或错误。四采样 PCF 候选破坏 TestScene3，已拒绝。下一步核对目标探针捕获能量与独立参考、天空分离和捕获阴影；不要继续靠扩大实时过滤或强行最低补光声称解决。细节见 DEV_HANDOFF.md。

当前状态：02847aef1 的球谐方向低通只是用户允许暂用的折中，不是最终根因修复，也不代表没有更好的方法。L0不变、L1×0.5、L2×0.125，减弱方向对比；八探针、遮挡、天空可见性及烘焙数据保持，反射捕获跳过滤波。用户接受当前TestScene3对照，但希望后续保留更多方向细节。

- [ ] 用独立射线积分或高质量离线辐照度建立参考，比较探针原始SH、截零前后值和最终插值；不能仅凭SH0平滑就认定方向项错误，不能用同一捕获后端自证正确。
- [ ] 沿原硬带和跨网格轨迹记录八个邻居的有效性、位置/偏移、空间/法线/距离权重、归一化分母和各阶SH贡献；区分真实遮挡、负值截断、权重变化与低能量分布。
- [ ] 审计捕获/投影/解码的坐标和方向约定、法线贴图参与方式、线性HDR与卷积归一化、量化和能量损失。发现错误时优先纠正，不用额外模糊掩盖。
- [ ] 对比待验证候选：仅在振铃处自适应正则化或非负重建、先混合系数再求值与截零、几何法线用于筛选而着色法线用于响应；记录能量误差、方向细节、漏光和成本，不预先承诺哪种有效。
- [ ] 按证据评估局部放置/自适应布局或其他辐照度表示。当前Sponza已有2196个有效探针，不笼统归因于数量不足；不得启用27邻居，不靠降低烘焙阴影强度或提高曝光掩盖问题。
- [ ] 联合验收 Models/glTF/Sponza.j3o走廊/拱顶、Scenes/TestScene3.j3o及原墙脚色块：保留合理暗部，消除人工硬边、保持平滑，检查薄墙漏光和方向细节。固定曝光，对照当前折中与未滤波基线；覆盖四条渲染路径、Lighting/PBR、静态和移动视角并记录GPU成本。
- [ ] 更好方案通过后替换或减弱固定低通，记录数据兼容与恢复步骤。本项是待办，不追加实现；下次继续GI质量优化时主动提醒。

复现相机：位置(5.7239,2.0553,-3.7907)，朝向(-.9887,-.1464,.0332)，FOV45。验收重点是平滑，不是偏黑就是错。诊断见DEV_HANDOFF；build产物为本机临时文件，跨机器需重新生成。

## 2026-11-19 UE4-style SDR implementation checkpoint

AutoExposureFilter now offers opt-in UE4Histogram brightness/adaptation semantics; ColorGradingFilter offers opt-in AP1 Film and cached CombineLUTs/ApplyColorLUT. Old modes remain defaults for compatibility. Targeted native numeric tests passed; see DEV_HANDOFF.md for limitations (sparse metering, approximate tint, no EV100/pre-exposure/PQ, partial tonal grading) and commands. Full UE visual parity, physical-camera exposure and later TAA remain pending.

## 待办：中立碰撞资产与物理后端适配（2026-11-19）

用户要求记录设计，暂不实现。目标是 SDK 一次烘焙，J3O 保存中立描述，游戏运行时由所选物理后端创建对象；不在同一个 J3O 中直接保存两套 JBullet/Minie 对象。

- [ ] 定义版本化 CollisionDescription：基本形状、凸包、三角网格、复合形状、顶点/索引、子形状变换，以及质量、摩擦、恢复系数、碰撞组等参数。明确坐标、单位、缩放与数据所有权。
- [ ] SDK 烘焙与预览使用中立描述，保存不依赖具体物理实现类；无物理库时仍可加载/显示模型并保留描述，加载模型与启用物理分开。
- [ ] 提供独立 JBullet/Minie 适配模块。配置显式选择后端；仅存在一个实现时允许自动选择，冲突或不支持时清晰报错。两库含同名类，不通过普通 classpath 共存或反序列化失败后偷偷切换解决。
- [ ] 明确共同能力与后端扩展参数；不支持的形状/参数需报告或使用用户明确选择的降级方案，不静默丢失。相同描述不承诺模拟数值完全相同。
- [ ] 按需设计后端专用烘焙缓存，包含后端/版本/平台/输入摘要；缓存失效时从中立描述重建，评估大型网格加载耗时和存储成本。
- [ ] 为旧 J3O 中直接保存的 RigidBodyControl/碰撞形状提供迁移工具：在匹配原物理库的环境读取，提取中立描述后另存；失败保留原资产，不依赖错误后端强行读取。
- [ ] 定义线程、启用/禁用、重复实例、物理空间注册与释放生命周期；提供详细中文分步用法注释及 SDK 操作说明。
- [ ] 分离后端运行环境测试：同一中立资产分别在 JBullet/Minie 下保存重载、创建碰撞与交互；验证无后端加载、依赖冲突、缓存失效、复合形状/非均匀缩放、迁移失败保护和性能。

背景：TestGame 引用了 JBullet，而 SDK 使用 Minie 9.0.3；现有碰撞对象反序列化产生 BVH 异常及后续空指针。此待办不表示现有资产已自动兼容，也不授权本轮实现。

## 待办：TAA / GTAO / FSR 与 LWJGL Vulkan 后端（2026-11-19）

用户要求记录以下路线，本轮仅更新文档，不实现功能。FSR 2 是时序超分辨率，不含帧生成；FSR 3 才引入帧生成。不要因版本混淆，把 TAA 或 FSR 2 的全部开发依赖于插帧呈现框架。

建议顺序：必要 RHI 边界 → 公共时序基础与 TAA → GTAO → FSR 1 → 完整 Vulkan 后端 → 官方 FSR 2 → FSR 3 帧生成。FSR 1 不依赖时序基础，可按需求提前；GTAO 空间采样可先独立验证。无需等待完整 RHI 才开始效果开发，以 OpenGL 验证效果，再由 Vulkan 验证后端抽象。

- [ ] 整理现有 Renderer/FrameGraph 的后端边界：资源描述、访问状态、同步、能力查询、渲染/输出尺寸及资源生命周期。逐步消除上层 GLFence、OpenGL barrier 位等依赖，保留现有 OpenGL 行为。RHI 不等于逐项包装 LWJGL 调用；避免一次性重写成熟路径。
- [ ] 建立公共时序契约：明确速度单位、方向、坐标空间与 jitter 约定；保存相机/对象前帧矩阵、骨骼/Morph 前帧形变；覆盖 GPU Scene 与传统提交。历史按视口管理，定义切场景、相机切换/瞬移、resize、动态分辨率、曝光变化和效果启停的失效/恢复规则。
- [ ] TAA 主要参考 UE4 的公开技术资料和完整时序管线设计：重投影、深度/遮挡显露拒绝、历史裁剪、抗闪烁和清晰度控制。Three.js TRAANode 可辅助核对实现；传统 TAARenderPass 不含重投影，不能作为动态场景成熟 TAA 的基础。明确透明、粒子、Alpha Test、形变和无速度材质的处理/回退，不把静态累积当作完成。
- [ ] GTAO 主要参考原论文和 Intel XeGTAO，Three.js GTAOPass 用于辅助理解/调试。先验证深度预处理、空间采样和边缘滤波，再按需复用速度/历史进行时序稳定。明确 Forward/Deferred 输入及中性 GBuffer 的模型解码，AO 按光照分量合成，避免整体压暗直接光/发光，以及与 GI/天空已有遮挡重复计暗。
- [ ] FSR 1：在 OpenGL 上先实现空间升采样，分离渲染与输出分辨率，明确抗锯齿、Tonemap、EASU/RCAS 和 UI 的执行顺序与色彩约定；UI 按输出分辨率绘制。保留原生分辨率及关闭后的恢复路径，不要求速度或历史。
- [ ] 基于 LWJGL 完成 Vulkan 后端：资源/描述符绑定、pipeline 状态、shader 编译与变体、命令提交、队列/图像布局同步、延迟释放及 swapchain resize/out-of-date 生命周期。处理 OpenGL/Vulkan 深度和坐标差异，采用同一组效果/场景回归；SDK 原生窗口/AWT 嵌入兼容单独评估，不默认随 GLFW 窗口支持完成。
- [ ] Vulkan 稳定后集成官方 FSR 2 时序超分辨率：复用速度、jitter、深度、曝光和历史重置契约，补齐所需透明/reactive 输入；作为常规 TAA 的替代重建选项，不默认在 TAA 后再次累积。官方 DX12/Vulkan 后端路线优先于自行维护 OpenGL 移植。
- [ ] FSR 3 帧生成最后开发：在 Vulkan 呈现框架上验证交换链代理/调度、GPU 同步、帧节奏、输入延迟、UI 合成及窗口模式切换；分别统计真实渲染帧率与插帧输出帧率。预留接口不代表已支持插帧。
- [ ] 每阶段独立开关、保留旧路径与恢复步骤，并提供 jme3-examples 可运行示例、源码中文分步用法和自动验证入口。固定曝光/分辨率比较静止、相机移动、骨骼/Morph、透明、细线/高光、遮挡显露、切场景及多视口；覆盖四条现有渲染路径和 Lighting/PBR，记录 GPU 时间、显存、拖影/闪烁及历史重置结果。不能仅用静态截图验收 TAA。

参考资料（算法与架构参考，不表示照搬实现或已达到同等质量）：

- [Epic Temporal Upscalers](https://dev.epicgames.com/documentation/en-us/unreal-engine/temporal-upscalers-in-unreal-engine)：时序输入、曝光与调试契约。
- [GTAO 原论文](https://www.activision.com/cdn/research/PracticalRealtimeStrategiesTRfinal.pdf) 与 [Intel XeGTAO](https://github.com/GameTechDev/XeGTAO)：遮挡算法与工程参考。
- [Three.js TRAANode](https://threejs.org/docs/pages/TRAANode.html)、[TAARenderPass 源码](https://github.com/mrdoob/three.js/blob/dev/examples/jsm/postprocessing/TAARenderPass.js)、[GTAOPass](https://threejs.org/docs/pages/GTAOPass.html)：区分重投影与静态累积，辅助验证。
- [AMD FSR 1](https://gpuopen.com/fidelityfx-superresolution/)、[FSR 2](https://gpuopen.com/fidelityfx-superresolution-2/)、[FSR 3 集成文档](https://gpuopen.com/manuals/fidelityfx_sdk/techniques/super-resolution-interpolation/)：集成时重新核对选定版本的能力、后端与接口要求。

下次继续时序效果、RHI 或 Vulkan 工作时主动读取本节；此待办不授权自动开始实现。
