# 渲染质量升级路线与跨机器开发交接

更新日期：2026-11-16。本文是开发方案，不是已完成功能列表。

2026-11-20：本地 TAA 新增静态边缘连续帧回归，修正重建坐标、深度量化拒绝和退化颜色裁剪。128×96 单位对比度测试的最坏通道变化从 1.0 降至约 0.189；仍有残余波动，新增采样开销待测。详见 DEV_HANDOFF.md。

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
- 保留现有 SDR 输出。Windows 独立 LWJGL3 窗口的 FP16 scRGB 输出默认关闭，开启时使用 OpenGL/D3D11 共享纹理；互操作不可用时继续呈现 SDR。已验证范围见 DEV_HANDOFF.md。
- 预曝光不是简单乘一个系数：若采用，必须统一历史帧、Bloom、反射、GI 和捕获的数据尺度，并提供调试视图。

验收：相同材质、光照和曝光下，对比 Forward/Deferred 与场景捕获；检查灰阶、饱和高亮、黑位、亮部渐变、亮度连续性和重复编码。原有低质量/兼容路径应可恢复。

#### HDR 显示输出后续清单

下次继续 HDR 显示输出或 Vulkan 后端时，先提醒用户查看本清单。Windows 共享纹理已经接入，原生 Vulkan 呈现留到 Vulkan 渲染后端具备接入条件时。

- [x] 在 RTX 5080 上通过 `WGL_NV_DX_interop2` 将最终 OpenGL 帧写进 SDL 的 D3D11 FP16 纹理，共用现有 HDR 开关和 scRGB 交换链。
- [x] 比较共享路径和旧路径的近黑灰阶、高于参考白的高光和朝向。在 TestScene2 原相机位置验证窗口和全屏的 HDR/SDR 切换。
- [ ] 构建后验证 Windows 内嵌 3DViewer 的原生 HDR 预览，覆盖同一场景的 HDR/SDR 切换、缩放、拖靠和鼠标操作。原生子窗口与 AWT 命中测试探针已通过。
- [ ] 补测其他显卡、混合显卡、焦点切换及跨 SDR/HDR 显示器移动，测量 GPU 帧时间和互操作同步等待。
- [ ] Vulkan 渲染后端具备接入条件后，验证原生 HDR 交换链可用的格式与色彩空间组合、参考白与高光映射，以及 SDR 回退。

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

## 待办：统一 GI 探针布局与手动密度体积（2026-11-22）

目标是保留在 SDK 中手动摆放不同密度盒子的工作流。GI 覆盖体积定义烘焙范围，密度体积定义局部细分规则。多个密度体积可以重叠，统一烘焙生成一套分级探针数据。

- [ ] 先验收现有 GI 页池和流送。用 `Scenes/TestScene5.j3o` 验证两份旧 `.giprobe` 都能加载并参与渲染。分别显示未加载、已读入、上传中、渲染中和页池空间不足，比较旧 64 MiB 图集与可容纳两份数据的配置。把容量与上传耗时分别记录。
- [ ] 定义全局覆盖、基础间距和细分层数。让烘焙器在几何体附近自动加密，空旷区域保留粗探针。评估静态灯光附近的细分与预算裁剪。现有 `GiProbeStreamingData` 是单个规则网格的粗层与细块流送基础，后续统一布局仍需单独实现和验收。
- [ ] 增加可重叠的手动密度体积。评估参考 UE4.27.2 的允许级别范围，`0` 为最细，`[0,0]` 强制最细，`[1,3]` 排除最细并允许较粗级别。明确 JME 的层数、实际探针间距和范围上限。重叠区域在烘焙时合并约束，参考 UE 对两个端点分别取较小值，并测试反转体积顺序得到相同结果。
- [ ] 运行时按世界位置查找烘焙后的砖块。细块完成上传后使用细探针，细块缺失、卸载或预算不足时继续使用粗层，跨粗细边界平滑过渡。保留旧 J3O 与独立 `.giprobe` 的读取，以及独立烘焙地图块之间的原有边界混合。
- [ ] 用 `TestScene5` 对照验证仅自动细分、添加密度体积、多个密度体积重叠、反转放置顺序及细块卸载回退。固定曝光和强度，检查接缝、漏光、局部细节、GPU 图集占用和上传峰值。

参考本地 UE4.27.2 的 `VolumetricLightmapDensityVolume.h`、`FScene::GetVolumetricLightmapAllowedMipRange` 和 `FStaticLightingSystem::ShouldRefineVoxel`。下次继续 GI、探针密度、粗细层流送或 Volume 重叠优化时，主动提醒本节待办并先检查验收项。

## 2026-11-19 histogram and film SDR implementation checkpoint

AutoExposureFilter now offers opt-in SceneHistogram brightness/adaptation semantics; ColorGradingFilter offers opt-in AP1 Film and cached CombineLUTs/ApplyColorLUT. Old modes remain defaults for compatibility. Targeted native numeric tests passed; see DEV_HANDOFF.md for limitations (sparse metering, approximate tint, no EV100/pre-exposure/PQ, partial tonal grading) and commands. Full UE visual parity, physical-camera exposure and later TAA remain pending.

## 待办：静态 Lightmap 与动态 Probe GI 分流主路线（2026-11-23）

用户确认后续主要优化方向：LightProbeVolume 完成烘焙后，将静态物体的间接光缓存到表面 Lightmap，运行时不再对所有静态像素重复执行八探针／方向遮挡查询；动态和局部动态物体使用探针场。Godot 的 LightmapGI 同时包含静态表面贴图和动态物体的 LightmapProbe 捕获数据，不能把其静态场景帧率解释为全场景逐像素探针的成本。本节优先于对全部静态表面持续执行复杂 Probe GI；旧分块 SH 方案保留为可选补充。

- [ ] 为物体定义 GI 用途与运动性：静态（变换／几何不变）、动态（可平移／旋转／形变）、局部动态（位置固定但可旋转）。明确节点继承、实例覆盖、SDK 编辑、J3O 序列化和变更提示；共享材质不能充当实例运动性标记。旧资产缺字段时沿用原路径，不自动改变画面。
- [ ] 静态物体参与固定几何烘焙并接收 Lightmap；动态和局部动态物体默认接收 Probe GI，不把允许旋转的对象当作固定姿态的普通 Lightmap 接收物。蒙皮、顶点动画和风动等即使位置不变也需按形变处理。明确各类型是否参与烘焙遮挡与反弹，避免把移动前的遮挡永久留在场中。
- [ ] 在现有 LightProbeVolume 烘焙完成后，按静态表面位置和法线求值并写入独立 Lightmap 资产；定义 UV2 展开／重叠验证、图集密度、边界扩展、mipmap、HDR 格式、资产引用和版本。先复用现有 GI 结果保证兼容；贴图缓存不能补回原探针场已缺失的空间细节。
- [ ] 评估方向性 Lightmap／SH 表面存储，以保留法线贴图对间接光的响应；如采用单 RGB 辐照度，明确其法线近似与画质损失。记录存储的光照量，禁止重复应用余弦卷积、方向低通或材质反照率。
- [ ] 运行时静态物体已有有效 Lightmap 时跳过 Probe GI 查询；动态和局部动态物体运行时采样探针，保留按位置／法线处理遮挡的能力。固定位置旋转物体可评估复用世界空间 SH，但应正确处理法线坐标及失效。明确“实时采样烘焙场”不代表动态物体的反弹或遮挡已经实时更新。
- [ ] Lightmap／Probe／SkyLight 保留完整与分离天空数据的原有语义，防止天光和 GI 重复叠加；GI 总开关同时控制两种接收路径，避免关闭探针后静态 Lightmap 仍残留。反射探针仍单独处理镜面反射。
- [ ] 定义静态物体移动、旋转、形变、材质变化及天空／灯光变更后的缓存过期检测、重烘焙提示与可选降级。缓存缺失／过期时保留可诊断的兼容路径，不黑屏、不覆盖用户原始资产；保留原全场景 Probe GI 作为独立对照选项。
- [ ] 先验证静态 Sponza 表面、移动角色、原地旋转物体与大型跨房间物体；检查薄墙／墙脚／背面／拱顶、法线贴图、GI 总开关、保存重载及天空组合。固定相机、实际内部分辨率和后处理，统计静态 Probe 查询是否消失，并报告总 GPU／CPU 时间、贴图与探针内存、加载开销和实测 FPS。

参考：[Godot LightmapGI](https://docs.godotengine.org/en/stable/classes/class_lightmapgi.html)、[LightmapProbe](https://docs.godotengine.org/en/stable/classes/class_lightmapprobe.html)。下次继续 GI 烘焙、物体静态／动态分类、Lightmap 或 Probe Volume 优化时主动提醒本节。当前只登记计划，不开始实现。

### 待办：半透明物体的 Lightmap 与 Probe GI 正确性

- [ ] 用半透明玻璃、透明度贴图的混合材质和 Alpha Cutout 网格建立对照。分别检查物体作为接收面时的间接光，以及作为烘焙物体时对探针距离矩、后方遮挡和间接反弹的影响。明确哪些行为由当前材质和渲染路径支持。
- [ ] 在 Forward 与延迟渲染的透明队列中，比较 GI 关闭、仅 Probe GI、Lightmap 驻留和卸载回退。覆盖静态和移动物体、两层半透明表面、Lightmap 部分覆盖及缺失 UV2。检查间接光是否遗漏、重复叠加、串到后方物体，或在切换时跳变。
- [ ] 记录可复现问题、预期结果及受影响路径，再决定是否修复。若需要修复，先验证透明排序、深度写入与烘焙接收规则，再补图像回归。下次继续 Lightmap、Probe GI 或半透明渲染优化时提醒本项。

## 待办：按空间分块缓存探针 GI 与无 GI 基线优化（2026-11-23）

用户要求登记并在后续继续时提醒。目标是减少逐像素重复探针查询、距离矩遮挡和 SH 求值；参考 Godot 的实例级 SH 复用思路，但不把大型建筑或跨室内外物体直接压成一组 SH。Godot 4.5 的 LightmapGI 探针捕获在几何实例 AABB 中心，对所在四面体的四个探针进行 SH9 插值；此说明不适用于其全部 GI 技术，也不代表大物体的空间遮挡被解决。

- [ ] 评估按空间块、几何簇或表面采样点建立独立 GI 缓存。大物体跨墙、跨房间或跨明暗区域时允许多个缓存位置，明确块大小、采样密度、边界共享及内存预算；不要假定网格的材质分组天然满足空间划分要求。
- [ ] 明确缓存存储的是 SH、入射辐照度还是遮挡／插值权重。现有可见性依赖接收位置和法线，且有每探针非负截断及可选平方根混合，不能把“先混合 SH 再求值”称为等价优化。明确近似范围，保留原全分辨率／原逐像素 GI 路径和独立开关，默认行为不变。
- [ ] 保留遮挡与漏光约束：探针自身烘焙有遮挡，不代表插值不会穿墙；四面体或空间分块也不自动构成遮挡边界。评估块内多个接收点／方向信息及不可信区域处理，验证薄墙、墙脚、背面、窗边、拱顶和大型跨房间物体；不以最低补光或跨墙模糊掩盖误差。
- [ ] 设计缓存失效与更新预算：静态块复用，变换／形变、体积参数、烘焙更新、砖块装卸和流送状态变化时按依赖刷新。保留天空可见性、天空强度与反弹光的数据语义，避免重复叠加；定义缺页、无有效样本和动态物体的降级路径。
- [ ] 优先复用现有烘焙数据构建独立运行时缓存，保护已有 J3O／.giprobe 格式与历史修复；确需新数据格式时另行设计版本标识、旧数据兼容和迁移方式，不静默覆盖用户资产。
- [ ] 同视角、同实际内部分辨率与同渲染设置，对比原逐像素、半分辨率和分块缓存。分别记录探针查询、缓存更新／重建／回退、上传与同步的 GPU／CPU 总耗时、显存和实测 FPS；不能只报告某个 Pass 变快，也不能用 GPU 时间倒数冒充窗口 FPS。检查静止及移动画面，记录局部最坏误差。
- [ ] 单独排查 GI 关闭时的原生分辨率基线，包括 GBuffer、Lighting、阴影、后处理、FSR2 和呈现开销。与 Godot 对比时先核对实际 GI 类型、网格实例划分、内部／输出分辨率和效果质量，不把全部帧率差归因于探针插值。

### 已评估候选：分块 SH 缓存

以下保留为主路线的可选补充，例如动态／局部动态对象的低成本接收，以及不适合表面 Lightmap 的情形；尚未实现。

- [ ] 空间距离与表面连通／几何法线共同分组，墙两侧分别缓存；平坦区域稀疏，窗边、墙脚、拱顶及明暗交界增加代表点并自适应细分。同一表面共享边界样点并归一化插值，不把整座建筑压为一组 SH，不为每个 GI 块强制增加 Draw Call。
- [ ] 缓存生成时，在代表点固定真实几何法线，以多个着色法线调用原完整 GI 求值，再拟合有效方向响应 SH9；16–32 个方向仅作原型起点，需交叉验证。系数允许正负，约束的是重建能量；读取时不重复原算法已应用的卷积／低通。
- [ ] 单独保留天空方向、可见性与覆盖语义。静态数据按依赖复用，控制流送和刷新预算；不可信块优先细分或加密，原路径降级需独立开关，避免把零散逐像素完整回退当作低成本方案。

提醒约定：下次继续 GI、LightProbe／Probe Volume、空间分块缓存或渲染性能优化时，主动提醒本节，并与“统一 GI 探针布局与手动密度体积”待办一并读取。当前只登记计划；不授权自动实现，也未设置定时通知。

## 待办：Probe 辐照度双格式烘焙与 WorldSettings 采样选项（2026-11-23）

用户原话（后一句修正前一句中的运行时 SH 转回八面体方案）：

> 目前的lightprobe是每个probe 9次采样读取9个SH，后续提供将SH转回八面体的选项，然后目前是一个probe4次方向切比雪夫评估，后续应该提供原始方案的一次切比雪夫评估，然后这些可以在worldsettings配置，后续的DDGI会继承这部分，帮我写入md上下文，push到远程fork

> 应该是烘焙时，先烘焙出八面体，然后基于八面体生成SH，对吧？然后同时序列化两份数据到磁盘，根据worldsettings配置进行选择读取，因为SH运行时转回八面体有点费时

用户进一步明确的实施方向，当前仅登记，未实现新格式、配置或 shader。新烘焙应先生成八面体方向辐照度，再由同一方向辐照度投影出 SH9；同时序列化八面体与 SH 两份数据。运行时根据 WorldSettings 选择加载和使用哪一份，不把“运行时把 SH 转回八面体”作为正常路径。后续动态 DDGI 复用本节的表示、读取和遮挡模式契约。

### 当前基线与目标

当前默认紧凑邻域每次网格查询枚举八个候选探针；参与者读取九个 RGB SH 系数重建辐照度，并对四个方向的距离矩分别作切比雪夫可见性评估，再混合概率。无效性、权重和覆盖检查可能提前退出；天空分离、元数据、粗细层／多体积还可能增加读取，不能简单把纹理语句次数当成显存事务数。既有宽邻域／表面布局仍可能走十六方向路径。

目标是提供两个独立配置维度，允许四种组合比较：

| WorldSettings 配置维度（逻辑名称，非已实现 API） | 可选模式 | 旧资产／默认行为 |
|---|---|---|
| Probe 辐照度表示 | SH9；Octahedral Irradiance | 保留 SH9 |
| Probe 方向遮挡评估 | 四次独立判定后过滤；单方向采样后一次判定 | 保留四次 |

单次辐照度纹理查询意味着方向辐照度已经预计算，不是把 SH9 无损压缩到一个 RGB；单次 texture 调用可能仍触发多个硬件过滤 texel。八面体模式仍需读取独立元数据、距离矩及可能存在的天空数据，不承诺整个探针只有两次总读取。

### 第一步：固定数据语义和兼容边界

- [ ] 明确八面体 RGB 存的是线性 HDR 入射漫反射辐照度 E(n)，以世界方向／着色法线查询；不是未卷积辐射亮度、材质反照率乘积、最终曝光颜色或反射 cubemap。
- [ ] 固定与现有 SH9 的基函数、坐标／手性、尺度和半精度归一化约定。当前 SH 表示已包含余弦卷积；从八面体 E(n) 投影得到 SH 后不得再次做余弦卷积。现有方向低通是否已经应用必须明确记录，禁止烘焙和运行时重复过滤；SH9 旧模式保持原行为。
- [ ] 同一份烘焙快照生成两种表示，沿用 TOTAL_IRRADIANCE 与 BOUNCED_IRRADIANCE_WITH_SKY_VISIBILITY 语义。天空可见性／方向、覆盖和过期状态独立保留，防止切换表示时丢失天空遮挡或重复叠加 SkyLight；局部反射仍独立处理。

### 第二步：重构烘焙输出顺序

- [ ] 采集探针方向辐射与深度，按已有场景捕获、阴影、多次反弹和探针有效性／重定位规则生成数据；不因新增表示退回未经验证的捕获或混合算法。
- [ ] 对方向辐射做漫反射积分／卷积，生成八面体 E(n)；对分辨率、精度、球面采样、边缘镜像／gutter 和过滤进行质量评估。八面体仍有积分与过滤成本，不能当作零成本的 cubemap 拷贝。
- [ ] 由同一八面体 E(n) 投影 SH9。投影必须按球面立体角加权，或用等面积球面方向采样八面体；不能对八面体 texel 直接等权平均。量化前投影优先，记录量化／投影误差并与原 SH 烘焙对照。
- [ ] 距离矩继续作为独立方向数据生成；明确 mean、second moment、variance／standard deviation 的含义和尺度。辐照度表示切换不得顺带改变现有遮挡精度、探针分类或重定位。
- [ ] 同时写出两份辐照度载荷并记录同一 bake revision／布局标识，确保不是两次独立捕获产生不一致的光照。多次反弹迭代的中间状态不发布为完整双格式资产。

### 第三步：版本化双格式资产与按需读取

- [ ] 在外部 GI 资产中增加版本、布局和编码标识、SH9／八面体分辨率及格式、独立载荷偏移／长度、完整性校验、烘焙版本与共享元数据目录；两种辐照度数据都保存到磁盘。
- [ ] SH9、八面体辐照度、距离矩和天空载荷独立分块／压缩，使选择 SH9 时无需读取解压八面体 RGB，选择八面体时无需读取解压 SH RGB。J3O 优先保存外部引用；若直接嵌入两份数据并被反序列化器全部展开，不能宣称已经实现选择性 I/O。
- [ ] 将有效性、重定位、表面链表／空间索引及其它元数据设为共享、表示无关的载荷。当前部分数据保存在 SH atlas 的 alpha 中，八面体路径不能为了读取这些元数据仍强制读取全部 SH 纹理；旧格式由加载器正确提取，不改写原资产。
- [ ] 新读取器继续支持旧 SH-only J3O／.giprobe 和现有粗层／细砖块流送。用户选择八面体但资产没有该载荷时，明确提示需要重新烘焙，并保留可用 SH 路径；正常加载不自动执行昂贵的 SH→八面体转换，不覆盖用户原文件。
- [ ] 不承诺新格式能被旧引擎读取；明确迁移、重新烘焙和恢复旧文件的步骤。写入失败保持旧资产，避免一份成功一份失败却标记烘焙完成。以实际页／探针统计双存磁盘增长与单表示 CPU／GPU 驻留量。

### 第四步：WorldSettings 与 SDK 选择流程

- [ ] 在 WorldSettings 中保存上表两个独立枚举配置；名字待实现时确定，本计划不是已经可调用的 API。缺字段保持 SH9＋四次判定，不根据载荷自动切换画面；半分辨率／FullFallback 与这两个维度正交。
- [ ] SDK 场景设置显示辐照度表示、遮挡模式、实际生效模式、缺失载荷提示和加载状态。序列化场景设置，Java 挂载场景后由运行时服务应用；场景加载但未挂载不创建 GPU 资源。
- [ ] 加载器／流送器先获得生效策略，异步只读所需辐照度载荷和共享数据；不在渲染线程同步读盘或逐探针转换。配置切换先准备新资源，再在帧边界一致发布，保留旧可用模式直至完成；更新相关时序历史，约定资源引用与释放。
- [ ] 处理多个 WorldSettings、多个视口／编辑器预览、不同场景同时选择不同模式的情况，不能修改共享纹理／材质导致相互覆盖；采样器／视图或绑定状态应隔离。

### 第五步：实现八面体辐照度读取

- [ ] 每个被选中的探针，根据世界着色法线编码八面体坐标，读取已经预计算的 E(n)。正确处理 tile 边缘、跨八面体折叠线、过滤与 atlas 邻探针串色，禁止把天空遮挡方向误当作辐照度查询法线。
- [ ] 第一阶段保持原八探针选择、有效性、重定位、接收平面／偏移、权重归一化、每探针非负处理及可选稳定混合；只替换每探针辐照度表示与求值。新路径是否保留更多方向细节单独验收，不能声称八面体与有限带宽 SH9 逐像素等价。
- [ ] 覆盖 Forward、Deferred、Tiled CPU/GPU、半分辨率缓存及完整回退。自定义材质仍使用材质自有声明，通用 GBuffer 约定不写死为某一种照明模型；能力不足保留旧路径。

### 第六步：实现单次切比雪夫候选

- [ ] 在每个探针上沿探针→接收点的方向读取距离矩，再进行一次切比雪夫判定；作为对照截图中原始 McGuire 风格方案的独立模式。仅改变方向采样／判定方式，原邻域和其它权重规则先保持；原示例的最小权重、variance bias、对比度压缩等额外差异若要引入，需分别设计，不捆绑移植。
- [ ] 明确采样过滤的位置：当前四次模式是各自评估后混合概率，单次模式若先线性过滤距离矩再评估，会改变估计器，并可能在前景／背景交界制造方差和漏光。选择可控的角向预滤波／保守处理，允许少量漏光但必须对照薄墙、墙脚、背面、拱顶与窗边。
- [ ] 保留当前半精度距离稳定性修复：不能把硬件过滤后的标准差直接平方，当成正确的混合方差；也不能忽略 E[d²]−E[d]² 的抵消误差。分别评估矩编码、精度和内存成本，四次模式继续使用原稳定解码。
- [ ] 单次／四次模式采用独立采样器或受控绑定，避免共享资产纹理过滤状态相互污染。评估宽邻域／表面布局的独立支持范围，不偷偷将其它模式的十六次路径改掉。

### 第七步：验收、迁移与 DDGI 复用

- [ ] 比较四种表示／遮挡组合；固定相机、实际内部／输出分辨率、曝光、阴影和 GI 数据。覆盖两个内置照明模型、四条渲染路径、体积重叠、粗细层流送、旧数据／缺失载荷、保存重载与开关恢复。
- [ ] 分别记录烘焙额外耗时、双存磁盘体积、选择性 I/O、CPU／GPU 驻留与上传、纹理查询和计算的总 GPU 成本、窗口实测 FPS及局部最坏画质误差；不只按九次→一次推断加速倍数，不继续用户已取消的常量替代测试。
- [ ] DDGI 复用表示描述、采样函数和 WorldSettings 遮挡策略。静态资产同时保存两份，不代表动态 DDGI 每帧必须同时更新两种表示；按活跃表示和消费者需求更新／投影，切换时预算化准备数据并保证版本／历史一致。
- [ ] 保留动态 DDGI 的分帧更新、历史融合、距离矩更新与同步预算，独立报告更新和接收成本；和静态 Lightmap／动态 Probe 主路线协调，避免已有静态 Lightmap 物体又重复计算动态探针漫反射。

提醒约定：下次继续 Probe 烘焙、SH／八面体表示、WorldSettings GI 配置、方向可见性、距离矩精度或 DDGI 时，主动提醒本节并先读取全部步骤。用户确认的正式方向是烘焙端双格式生成＋双存＋按需读取；运行时 SH→八面体不是默认方案。本轮仅文档与计划，不实现、不测试渲染、不设置定时通知。

## 待办：动态 DDGI 探针分帧更新与八面体存储（2026-11-23）

现有实现是烘焙探针场的运行时插值与方向可见性评估，和 DDGI 的部分接收计算相似，但没有完整动态探针更新；半分辨率只降低屏幕求值频率，不使其成为动态 DDGI。未来 DDGI 需额外计入捕获／射线查询、辐照度更新和距离矩更新，不能仅用当前静态查询开销估算整套成本。

- [ ] 建立有预算的分帧／分批更新队列：每帧只更新部分探针，按可见性、距离、场景／灯光变更和数据陈旧程度调度；设置单帧探针数、射线数、GPU 时间与上传上限，避免集中刷新卡顿，并明确初始未收敛／缺页的行为。
- [ ] 与上节“Probe 辐照度双格式烘焙与 WorldSettings 采样选项”共用数据语义、读取接口和配置；静态资产双存，动态更新按活跃表示执行。评估直接保存八面体方向辐照度，以替代每次更新投影成 SH、接收时读取并重建 SH9 的路径。比较完整更新链：采集辐射、方向卷积／滤波、边缘扩展及运行时方向查询；八面体仍需要辐照度预滤波，不能视为无转换成本。统计分辨率、格式、边界、显存与带宽，保留 SH／旧烘焙格式兼容。
- [ ] 距离矩同样评估八面体方向图。把“每探针单方向纹理查询＋一次切比雪夫判定”作为独立候选，对照现有四方向分别判定后混合的方式；一次 texture 调用也可能包含硬件多 texel 过滤，不能混淆计数。
- [ ] 单独验收距离矩先过滤可能在前景／背景交界制造方差、增加漏光的问题；评估角向预滤波、保守可见性、偏移与有效性处理，不能直接把一次判定默认替换旧四采样，也不靠最低补光掩盖误差。允许少量漏光的质量档位需可关闭，保留原路径对照。
- [ ] 设计历史混合／迟滞、失效重置、探针分类与重定位、动态遮挡／光源变更、多次反弹及陈旧数据可视化。分别测更新、采样、历史与同步总成本和收敛速度，验证动态物体、相机移动和薄墙场景。

下次继续 DDGI、动态探针、八面体辐照度／距离矩或 GI 性能优化时主动提醒本节。和“静态 Lightmap＋动态 Probe”主路线分别验收；当前只登记计划，不实现、不运行用户已取消的常量采样替代实验。

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

## 待办：图形 API 重构与 RHI（2026-11-22）

参考 [UE4.27 的 RHI 命令列表与后端命令上下文](https://dev.epicgames.com/documentation/en-us/unreal-engine/parallel-rendering-overview?application_version=4.27) 划分引擎和图形后端的职责。保留 jME 的 Renderer、FrameGraph 和材质体系，按现有渲染线程模型逐步迁移。

```text
Renderer / FrameGraph / 渲染 Pass
                  ↓
          RHI 资源与命令接口
              ↙       ↘
       OpenGL 后端    Vulkan 后端
              ↓           ↓
      LWJGL 或其他库   LWJGL 或其他库
```

- [ ] **确定 RHI 边界。** 定义资源描述与句柄、纹理与缓冲区上传/读回、绘制与计算命令、管线绑定、资源访问与同步、能力查询及呈现。FrameGraph 声明依赖和资源用途，后端将其转换为 OpenGL barrier 或 Vulkan 的访问状态、图像布局与同步。GL/Vulkan 的常量、原生句柄和绑定库类型留在各自后端。首版沿用现有渲染线程，独立 RHI 线程和并行命令录制等有测量依据后再评估。
- [ ] **先迁移 OpenGL。** 复用现有 GLRenderer 和 LWJGL 适配代码实现 OpenGL RHI。按清屏和基础绘制、纹理和离屏目标、计算和间接提交的顺序迁移。随后逐步收口 FrameGraph、GPU Scene、阴影、后处理、FSR OpenGL 后端、窗口呈现和 SDK 预览中绕过边界的 GL 调用。迁移阶段保留默认 OpenGL 路径及兼容回退。每一批先通过现有回归，再迁移下一批。
- [ ] **实现 Vulkan RHI。** 首先打通设备与交换链、清屏、带纹理绘制和离屏读回。再补齐描述符、管线与 shader 变体、命令提交、图像布局、队列同步、延迟释放与交换链重建。深度和坐标差异由后端或共享 shader 契约处理。独立窗口与 SDK 的 AWT 嵌入分别验收。
- [ ] **验收两套后端。** 同一个材质、场景和 FrameGraph Pass 能在两套 RHI 上运行，后端差异通过能力查询和明确回退处理。先用同一个示例验证绘制、纹理上传、离屏读回、窗口 resize 和资源释放，再覆盖 Forward、Deferred、GPU Scene 与后处理。用模块依赖或源码检查约束上层不直接引用 LWJGL 的 GL/Vulkan 接口，并记录 OpenGL 迁移前后的画面、帧时间与显存。

本节是计划，RHI 和 Vulkan 尚未实现。时序效果的现有 OpenGL 实现和后续依赖见下一节。

## 待办：游戏发行包保护与核心 Shader 二进制发布（2026-11-23）

发行包的目标是减少源码和制作素材暴露，提高逆向成本。客户端字节码、原生机器码和 Shader 中间码仍可被分析。开发构建保留 GLSL 源码与热重载，保护模式在发行构建中验证。

- [ ] 建立独立的现代 ProGuard 和 `jpackage` 发行任务。旧 SDK `Obfuscate Build` 仍依赖 Java 8 的 `jre/lib/rt.jar`，先评估替换并验证 JDK 25。第一阶段只混淆游戏业务代码。保留主入口、JNI 方法与类名、J3O 里已有的自定义 `Control` / `Savable` 类名和反射构造入口。内部保存混淆映射，用实际安装包验证旧 J3O、动画链接、DDS 和各平台 JNI。
- [ ] 用发行清单限制包内资源，只交付运行所需的 J3O、DDS 和运行时依赖。排除原始 FBX、PSD、源码、SDK 工程、混淆映射和调试构建产物。服务端保管 API 私钥、授权签发密钥及联网权威判定。离线授权若需要，客户端只携带验签公钥。原生库或 GraalVM Native Image 作为后续评估项，验证反射、资源、JNI 和目标平台。
- [ ] 增加 Shader 离线编译任务。先选一条核心材质技术与一个 Shader 变体验证，再逐步覆盖发行所需的 GI、光照和后处理。沿用 `.j3md` 技术与材质参数，构建时解析 GLSL `#import`、展开所需 defines 并生成按后端和变体区分的二进制与绑定元数据。资产清单按 Shader 阶段、入口、变体、目标环境和版本定位产物。
- [ ] 调整引擎的 Shader 资产加载、技术选择与渲染后端。桌面 OpenGL 验证 OpenGL 4.6 或声明 `GL_ARB_gl_spirv` 的上下文能加载并特化 **OpenGL 目标环境**的 SPIR-V。Vulkan 接入后使用单独编译的 Vulkan 目标产物。`glProgramBinary` 依赖驱动与硬件，只评估为可失效的本机缓存。对缺少 SPIR-V 支持的 OpenGL 4.3、LWJGL 2 等目标，明确由兼容发行包提供 GLSL 或提高保护版最低要求。保护版缺少受支持的二进制时给出可定位的错误。
- [ ] 验证核心 Shader 及其私有 `.glsllib` 源码不会经游戏资源、引擎 JAR 或回退包意外进入保护版。对照 GLSL 版与二进制版的渲染结果，覆盖材质参数、骨骼动画、GL 环境能力差异、变体缺失、驱动更新和启动耗时。记录各后端、显卡与驱动的实测覆盖。

参考 [OpenGL SPIR-V 扩展规范](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_gl_spirv.txt) 和 [OpenGL 4.6 Core 规范](https://registry.khronos.org/OpenGL/specs/gl/glspec46.core.pdf)。下次继续游戏发行、混淆、核心 Shader 发布或渲染后端改造时，主动提醒本节并先确认目标平台。当前为计划。

## 待办：TAA / GTAO / FSR 与 LWJGL Vulkan 后端（2026-11-19）

用户要求记录以下路线，本轮仅更新文档，不实现功能。FSR 2 是时序超分辨率，不含帧生成；FSR 3 才引入帧生成。不要因版本混淆，把 TAA 或 FSR 2 的全部开发依赖于插帧呈现框架。

建议顺序：必要 RHI 边界 → 公共时序基础与 TAA → GTAO → FSR 1 → 完整 Vulkan 后端 → 官方 FSR 2 → FSR 3 帧生成。FSR 1 不依赖时序基础，可按需求提前；GTAO 空间采样可先独立验证。无需等待完整 RHI 才开始效果开发，以 OpenGL 验证效果，再由 Vulkan 验证后端抽象。

- [ ] 整理现有 Renderer/FrameGraph 的后端边界；图形 API 迁移步骤与验收见[图形 API 重构与 RHI](#待办图形-api-重构与-rhi2026-11-22)。
- [ ] 建立公共时序契约：明确速度单位、方向、坐标空间与 jitter 约定；保存相机/对象前帧矩阵、骨骼/Morph 前帧形变；覆盖 GPU Scene 与传统提交。历史按视口管理，定义切场景、相机切换/瞬移、resize、动态分辨率、曝光变化和效果启停的失效/恢复规则。
- [ ] TAA 主要参考公开技术资料和完整时序管线设计：重投影、深度/遮挡显露拒绝、历史裁剪、抗闪烁和清晰度控制。Three.js TRAANode 可辅助核对实现；传统 TAARenderPass 不含重投影，不能作为动态场景成熟 TAA 的基础。明确透明、粒子、Alpha Test、形变和无速度材质的处理/回退，不把静态累积当作完成。
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

## 2026-11-19 TAA initial source checkpoint

The user subsequently authorized TAA/velocity implementation. TemporalAAFilter and selectable base-pass/separate opaque velocity plus optional Transparent velocity are now implemented locally; no full Depth Prepass was added. See DEV_HANDOFF.md for usage, tested synthetic cases and limits. This supersedes the earlier plan-only status for this subset, not the remaining roadmap.

Follow-up native GPUScene motion is implemented: rigid instances retain indirect/MDI drawing with GPU-resident previous transforms and visibility, for both opaque velocity modes. Shared-batch/MDI/compaction/Hi-Z and history-edge tests pass; see DEV_HANDOFF.md. CPU deformation snapshots for ordinary animated geometry, single-layer transparent motion and invalid-sky rejection remain initial constraints. Broader scene quality/performance validation, SDK integration and mature temporal reconstruction remain pending. GTAO, FSR, RHI/Vulkan and frame generation are not implemented by this change.

## 2026-11-20 shader presentation / FSR source checkpoint

User subsequently authorized default shader output and immediate FSR2 OpenGL integration. Working source now adds AppSettings.ShaderSrgbOutput (default On for LWJGL3 windows/application AWT panels), UpscalingPass with FSR1 EASU/RCAS and an FP32 FSR2 OpenGL backend, render/output dimension separation, native-size GUI, and FSR2 AA bypass/restoration. FPP phases distinguish spatial reconstruction after Tonemap from temporal HDR reconstruction before exposure/Tonemap; custom filters must declare their phase. Explicit FPP format and upscaling configuration round-trip in J3O. This supersedes plan-only status for this subset, not the RHI/Vulkan/GTAO/frame-generation backlog.

GPU synthetic four-path switching/motion/disocclusion/alpha/GUI and static-edge regressions pass on RTX5060Ti; core/effects serialization and independent SDK controls/settings tests pass. See DEV_HANDOFF.md for source provenance, entry points and exact limits. Reactive masks can be supplied; automatic transparent-color difference capture, broader animation/particle/multi-GPU/real-scene quality and production memory/performance evaluation remain pending. No full SDK deployment or claim of UE/AMD cross-platform quality parity. Preserve these limits when continuing on another machine.

## 待办：多光源阴影容量与性能（2026-11-22）

- [ ] 目前多光源阴影容量有限，需要优化，重点覆盖 PointLight 和 SpotLight。用户报告 `Packed shadow atlas exceeds texture size or byte budget`；源码默认预算 128 MiB，另有 64 个活跃阴影灯限制。本次异常来自图集尺寸/预算检查，不等同于命中 64 灯限制；未取得运行时尺寸和预算，不能确定两个条件中的具体触发项。
- [ ] 优先评估共享深度图集直接绘制、按屏幕影响范围分配分辨率、更紧凑且稳定的分配器以及明确的超预算策略。当前各灯独立深度贴图再复制到 RGBA32F 图集，存在重复存储、复制和打包空隙开销。
- [ ] 参考本机 `H:/MyFiles/Sources/TileBasedShadows`：四叉树图集、屏幕范围自适应分辨率、GPU mesh/light 相交筛选及间接绘制值得移植设计；点光源四面体投影作为独立候选。示例只实现点光源，聚光灯需补投影/锥体筛选。分配失败使用 assert，每帧重新分配，不能视为已有缓存或稳健降级。
- [ ] 分阶段接入 ShadowManager、FrameGraph、阴影采样及 GPU Scene；保留普通几何体、骨骼/Morph、Alpha Test、双面材质和不支持 GPU 能力时的兼容路径。不要将主相机可见物体列表直接作为投影物体列表。保持现有偏移/接触缝修复。
- [ ] 比较等画质下 CPU/GPU 时间、显存、复制量、灯数量与分辨率；覆盖密集重叠灯、相机运动、动态投影物体、分配溢出、四条渲染路径和 Lighting/PBR。四面体的几何放大、接缝与过滤成本需实测，不能承诺固定加速比例。

本轮仅源码评估和待办记录，未移植、未运行外部示例、无性能实测。直接复用外部源码前确认授权。建议先优化现有六面点光源/单面聚光灯图集，再实现 GPU 批量提交，最后对比四面体方案；不依赖先完成 Vulkan/RHI。

讨论结论与实施顺序：完整集成预计属于中到大型改造，以下规模是源码评估，不是工期或性能承诺。

1. 共享深度图集直接绘制、动态分辨率、稳定分配与明确超预算策略：中等改动，优先解决当前容量与复制开销；先保留点光源六面、聚光灯单面投影。
2. GPU mesh/light 筛选与批量间接提交：较大改动，复用现有 GPU Scene 基础，减少 CPU 遍历与提交；聚光灯需补锥体筛选、投影及采样。
3. 四面体点光源：较大改动，作为可选方案独立验证；不能以四面替代六面或一次提交就推断等画质下必然更快。

提醒约定：用户要求记得提醒优化。下次继续阴影、PointLight/SpotLight、多光源性能或阴影图集溢出问题时，主动提醒本节待办并先读取上述评估。此为接续工作时的提醒，不是定时通知；记录计划不授权自动开始实现。

讨论结论与实施顺序：完整集成预计属于中到大型改造，以下规模是源码评估，不是工期或性能承诺。

1. 共享深度图集直接绘制、动态分辨率、稳定分配与明确超预算策略：中等改动，优先解决当前容量与复制开销；先保留点光源六面、聚光灯单面投影。
2. GPU mesh/light 筛选与批量间接提交：较大改动，复用现有 GPU Scene 基础，减少 CPU 遍历与提交；聚光灯需补锥体筛选、投影及采样。
3. 四面体点光源：较大改动，作为可选方案独立验证；不能以四面替代六面或一次提交就推断等画质下必然更快。

提醒约定：用户要求记得提醒优化。下次继续阴影、PointLight/SpotLight、多光源性能或阴影图集溢出问题时，主动提醒本节待办并先读取上述评估。此为接续工作时的提醒，不是定时通知；记录计划不授权自动开始实现。

## 待办：虚拟纹理（VT）模块重构（2026-11-22）

- [ ] 盘点此前 VT 实现，区分可复用代码、工程专用逻辑和需要补齐的能力。将通用部分整理成 jME3 引擎模块，明确资产格式、页表、反馈、驻留与淘汰、上传预算、调试统计和缺页回退。
- [ ] 单独评估 LightProbe 烘焙。确定离屏捕获如何预取所需纹理页、等待驻留或选择明确的低分辨率回退；检查六面捕获的清晰度与一致性、反复烘焙的稳定性和显存成本。
- [ ] 先用现有 VT 样例验证迁移，再用大场景验证流送峰值、缺页和 LightProbe 烘焙。与现有表面卡片流送待办协调资源预算。

## 待办：Virtual Shadow Maps（VSM）（2026-11-22）

- [ ] 设计虚拟阴影页表、物理页池、请求和更新预算，定义灯光与投影物移动时的缓存失效、缺页处理和旧阴影路径回退。
- [ ] 建议先验证平行光的分级范围和跨帧缓存，再评估 SpotLight、PointLight。与现有多光源阴影容量优化分别立项，共用场景和指标比较画质、帧时间与显存。

下次继续 VT、纹理流送或 LightProbe 烘焙时主动提醒 VT 重构；继续阴影、阴影图集或多光源优化时主动提醒 VSM。两项均为开发待办，记录不授权启动实现。

## 天光 SH 求值优化待办（2026-11-23）

用户要求参考 GetSkySHDiffuse()，后续提醒如何优化天光；这里只登记计划，不开始实现。

- [ ] 阅读参考实现 Engine/Shaders/Private/ReflectionEnvironmentShared.ush 中 GetSkySHDiffuse / GetSkySHDiffuseSimple，并追踪天空 SH 系数的 CPU 预处理和上传。实施前核对本地参考版本，不直接套用其系数布局。
- [ ] 审计当前 SkyLight 的辐亮度、辐照度、SH 基函数顺序、坐标系、余弦卷积与归一化约定，避免重复卷积、重复乘 PI 或与烘焙 GI 重复叠加。
- [ ] 评估把方向无关的 SH 基函数常数和漫反射卷积权重预合并到系数，仅在天空资源或强度变化时更新；逐像素用打包向量点积及二次多项式求值，保持原有画面和动态更新语义。
- [ ] 分别评估完整 SH9 与可选低阶求值的 GPU 成本和方向细节损失。低阶不是默认替换；保留原路径开关以便对照和恢复。
- [ ] 检查负瓣、振铃、近黑裁剪及 FP16/FP32 误差。不要把 max(0, SH) 当成通用消除硬黑块方案；同时检查 HDR/SDR 最终输出，区分照明误差和呈现问题。
- [ ] 使用常量天空、强方向天空、旋转环境和实际室内场景，验证漫反射、天空遮挡、分离天光 GI、材质和 Forward/Deferred 一致性；记录 GPU 时间、更新成本和误差后再决定采用方案。

提醒约定：下次继续天光、SkyLight、SH 求值或 GI 优化时，主动提醒本节并说明上述优化步骤。此为接续工作提醒，不是定时通知；待办本身不授权自动实现。
