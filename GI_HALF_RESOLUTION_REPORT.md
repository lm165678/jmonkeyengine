# 半分辨率 Probe GI / FullFallback 对比报告

状态说明：下文性能与无回退差分记录的是较早的最近邻版本，用于保留实验依据，不能当作最新平滑重建的性能结论。最新无回退分支已改为表面引导加权重建；拱顶相对 L1 差异由 3.648% 降至 1.816%，区域回归 approximateMax=1、56 组遮挡差分 max=2，关闭后恢复差 0。新平滑版本未完成最终 FPS 复测，用户已取消常量替代实验。完整当前状态以 DEV_HANDOFF.md 为准。

日期：2026-11-23。引擎基线 `2f2e18698`，SDK 基线 `e32e9a63`，加本轮工作区修改。未提交或推送；未替换已安装 SDK 或导出引擎依赖。用户 J3O、GI 烘焙资产和游戏源码未修改。

## 结论

完整回退确实是本次实现的主要成本。关闭后能显著降低 GI GPU 耗时，但区域边界、薄几何、曲面处的误差也增大，不能作为无损优化。半分辨率总开关默认关闭，完整回退默认开启。两个选项独立保存于 WorldSettings，不更改烘焙格式，无需重新烘焙。

TestScene5 原生内部 1920×1080 的离屏测试中，关闭回退使整帧 GPU 时间从约 7.17 降至 4.54 ms，耗时减少 36.7%；这不是实际窗口 FPS。独立窗口启用了场景已有 FSR2，真实内部分辨率不同，其平均 FPS 从约 105 升至 135，约提升 28.5%。两组结果不能直接相互换算。

## 实测环境与方法

- Intel Core i7-14700KF；NVIDIA GeForce RTX 5060 Ti，驱动报告显存 16311 MiB；NVIDIA 576.88；OpenGL 4.3；JDK 25.0.5+8；Windows。
- TiledDeferredGPU；VSync 关闭、不限帧、禁用失焦暂停/限速；音频和 FPS/统计 HUD 关闭；固定模拟步长 1/60 秒。保留场景后处理和原 GI 参数。
- 三种模式依次为：原全分辨率、半分辨率＋回退、半分辨率无回退、半分辨率无回退、半分辨率＋回退、原全分辨率。每组在同一进程交替测试，每个模式重复两次。
- GPU 测试：OffscreenSurface，每阶段预热 600 帧、采样 500 帧；异步 GL timestamp。表格是两个阶段各自中位数的平均；GI＋Lighting 是按同一帧求和后再取中位数，包含新增全部 GI Pass，未只看 Lighting。
- FPS 测试：独立 Display 窗口，每阶段预热 600 帧、采样 1200 帧，禁用上述 GPU 计时查询。每种模式合计 2400 帧；平均 FPS = 总帧数 / 总实际帧间隔，使用 System.nanoTime，包含窗口呈现。窗口输出固定 SDR，已有 FSR2 保持开启。模拟时间固定不用于计算 FPS。
- 相机：TestScene5 为 (0,0,10)、朝 -Z；TestScene2 拱顶为 (7.4842,1.3443,-3.5689)、方向 (-0.9641,0.2559,0.0708)。关闭 Start 自动设置相机。不是所有 Main.java 默认运行配置的绝对帧率。
- FPS 与 GPU 耗时分开采集；窗口呈现、FSR2、CPU/桌面调度和后台竞争均可能影响 FPS。给出两轮范围，不把小差异当成稳定收益。未用 GPU 毫秒的倒数冒充实测 FPS。

## 独立窗口：分辨率与实际平均 FPS

“内部”来自实际 GBuffer 尺寸，“输出”来自窗口物理帧缓冲。原模式在完整内部尺寸计算 GI；半分辨率 GI 每轴向上取整除以二，直接光和 GBuffer 分辨率保持不变。

| 场景 | 输出分辨率 | 场景内部 | 半分辨率 GI | 原全分辨率 GI | 半 GI＋回退 | 半 GI 无回退 |
|---|---|---|---|---:|---:|---:|
| TestScene5 | 1920x1080 | 1281x720 | 641×360 | 105.22 | 107.29 | 135.19 |
| TestScene5 | 1280x720 | 854x480 | 427×240 | 274.13 | 289.61 | 294.26 |
| TestScene2 拱顶 | 1280x720 | 853x480 | 427×240 | 161.01 | 155.02 | 184.55 |

窗口里的 FSR2 比例：TestScene5 为 0.667，TestScene2 为 0.6666667。因此输出 1080p 不代表 GI 在 1080p 计算；如需评估原生内部 1080p，应看下一张 GPU 表。这里没有擅自关闭场景的超分辨率。

两轮平均 FPS 的范围如下，不能把某一轮最好成绩当成结论：

| 场景／输出 | 原全分辨率 | 半 GI＋回退 | 半 GI 无回退 |
|---|---:|---:|---:|
| TestScene5／1920×1080 | 101.33–109.43 | 104.44–110.29 | 124.97–147.23 |
| TestScene5／1280×720 | 268.08–280.45 | 280.94–298.82 | 276.97–313.84 |
| TestScene2／1280×720 | 160.63–161.39 | 154.94–155.11 | 182.25–186.90 |

窗口平均帧时间（由总采样时间求均值）：

| 场景／输出 | 原全分辨率 ms | 半 GI＋回退 ms | 半 GI 无回退 ms |
|---|---:|---:|---:|
| TestScene5／1920×1080 | 9.504 | 9.321 | 7.397 |
| TestScene5／1280×720 | 3.648 | 3.453 | 3.398 |
| TestScene2／1280×720 | 6.211 | 6.451 | 5.419 |

TestScene5／720p 的无回退两轮波动较大，和保留回退的范围重叠，不能据此声称稳定的大幅 FPS 提升。TestScene2 保留回退反而降低 FPS；独立开关必须保留。

## 离屏：GPU 阶段耗时

这组内部尺寸与窗口测试不同，不能把这些数字和上表 FPS 拼成同一帧。

| 场景 | 实际内部 | 模式 | GI 计算尺寸 | GI＋Lighting ms | 整帧 GPU ms |
|---|---|---|---|---:|---:|
| TestScene5 | 1920×1080 | 原全分辨率 | 1920×1080 | 4.4879 | 7.1740 |
| TestScene5 | 1920×1080 | 半 GI＋回退 | 960×540＋拒绝像素完整查询 | 4.1720 | 6.7909 |
| TestScene5 | 1920×1080 | 半 GI 无回退 | 960×540 | 2.0489 | 4.5442 |
| TestScene2 拱顶 | 853×480 | 原全分辨率 | 853×480 | 0.9054 | 2.0754 |
| TestScene2 拱顶 | 853×480 | 半 GI＋回退 | 427×240＋拒绝像素完整查询 | 1.0324 | 2.2117 |
| TestScene2 拱顶 | 853×480 | 半 GI 无回退 | 427×240 | 0.4524 | 1.6092 |

TestScene5 保留回退时，GI/FullFallback 约 2.146 ms；关闭后该 Pass 不注册、不执行，也不申请它的两个输出纹理。重建从约 0.385 增至 0.415 ms，因为拒绝像素还要选择附近的有效缓存；GI 总时间仍从约 4.172 降至 2.049 ms。该差额并非单独纹理采样指令成本，包含 Pass 和资源变化。

TestScene5 原生内部 1080p 中，约 15.8% 的 GI 接收像素被拒绝。保留回退时总完整查询数约为原来的 40.8%；关闭后约 25%，拒绝像素不再查询探针。日志同时记录 rejected 与 fallback；关闭时前者非零，后者为零。

回退像素分散、线程组利用率、每像素查询复杂度和数据访问可能解释非线性耗时，但本轮未采集硬件 wave/cache 指标，不将这些推测当成已证实瓶颈。

## 画质与兼容性

半分辨率缓存保存材质响应之前的入射漫反射及天空方向/可见性/覆盖相关数据，直接光、镜面反射保持原路径。没有时间历史复用。默认质量路径按深度/位置、法线、曲率、体积/砖块边界和光照变化检查重建；失败像素执行原来的完整探针查询，SH、遮挡、混合规则不变。

关闭完整回退后，失败像素改用四个邻近代表点中位置最近的有效缓存；均无效时为零体积入射光和未遮挡天空。不会保留失败检查中途的部分累加结果，但可能产生漏光、缺失接触阴影或边界跳变。这不是等画质模式。

下面是同一模拟帧、同一相机的线性 SceneColor 浮点差分；不是最终显示 sRGB 数值。L1 是所有 RGB 绝对差之和 / 原图所有 RGB 绝对值之和，不是每个像素都只有该误差。

| 场景 | 回退 | 全图相对 L1 | 最大通道绝对差 | 99% 像素的最大通道差不超过 |
|---|---|---:|---:|---:|
| TestScene2 拱顶 | 开 | 0.219% | 0.006561 | 0.0000973 |
| TestScene2 拱顶 | 关 | 3.648% | 0.049725 | 0.001488 |
| TestScene5 | 开 | 0.105% | 0.005595 | 0.0000725 |
| TestScene5 | 关 | 0.465% | 0.122681 | 0.000488 |

四种比较都没有检测到 NaN/Inf，alpha 最大差为零。全图平均不能掩盖局部误差：关闭回退后拱顶、轮廓、细几何的差分明显，TestScene5 的局部最大误差也明显增大。

本机对照图（左：原全分辨率；中：对照模式；右：放大的红色误差）。展示图统一采用额外 +6 EV、Reinhard 和 sRGB 以便观察暗部，误差统计使用原始浮点数据，不使用这些展示转换：

- [TestScene2：保留回退](build/gi-cost-check/quality-s2-arch-comparison.png)
- [TestScene2：关闭回退](build/gi-cost-check/nofallback-s2-comparison.png)
- [TestScene5：保留回退](build/gi-cost-check/quality-s5-comparison.png)
- [TestScene5：关闭回退](build/gi-cost-check/nofallback-s5-comparison.png)

验证结果：

- Gradle `:jme3-examples:halfResolutionProbeGiSmoke` 成功：Forward/Deferred/Tiled CPU/Tiled GPU、区域混合、奇数尺寸 241×181、开关恢复。保留回退最大差 1 个 RGBA8 色阶；关闭半 GI 后逐像素恢复，差 0。无回退诊断模式在此边界用例最大差 102，不能宣称等画质。
- 56 组遮挡用例 Off/On/Off 差分：默认保护路径最大差 1，恢复差 0。
- GI 相关及开关持久化单元测试 54 项通过；两个独立开关保存/读取及原 streaming 预算保持测试通过。
- 天空分离模式 288＋6 方向用例、完整天空模式 288＋6 回退用例在此前本轮半 GI 默认保护实现通过；加独立回退开关后，最后重跑了区域和 56 组遮挡差分，未再次重跑全套天空矩阵。
- 旧 compact visibility 绝对断言在全分辨率和半分辨率均失败，值同为 [57,10,36,10,44,10,10]，期望第 5 项接近 57；本轮未修改原遮挡算法来掩盖此原有失败。差分通过不等于该绝对用例已修好。
- SDK GiPreview / EditorGiStreamingPanel 定向编译、Swing EDT 默认/联动/刷新/恢复检查通过；未执行完整 SDK 构建、模块安装和编辑器交互验收。此前另一台机器的墙脚/背面场景未完整复验。

## 使用方式

SDK 源码：场景设置 GI streaming 中，`Half-resolution GI (deferred)` 默认关；`Full GI fallback (quality)` 默认开，仅半 GI 开启时可操作；点 Apply 后保存 J3O。需要重建匹配的引擎及 SDK 才能在当前安装中使用。

普通 SimpleApplication 在应用线程配置已有 WorldSettings，保留自动场景服务：

```java
renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
WorldSettings world = scene.getControl(WorldSettings.class);
world.setHalfResolutionProbeGi(true);
world.setProbeGiFullFallback(false); // 关闭完整回退，仅作性能/画质对照
rootNode.attachChild(scene);

// 恢复质量保护：
world.setProbeGiFullFallback(true);
// 恢复原全分辨率：
world.setHalfResolutionProbeGi(false);
```

如果场景没有配置，创建 WorldSettings 并 addControl；如果已有配置在子节点，修改实际生效的那份，避免用新的空配置覆盖原后处理。手动管理并已关闭自动场景服务时，可调用 ViewPort 的同名方法。操作属于应用/渲染线程；加载资产本身不会执行这些服务，挂载后下一次更新生效。

半 GI 关闭时不新增 GI/HalfEvaluate、GI/Reconstruct、GI/FullFallback，恢复原 Lighting GI 路径；FullFallback 选项被忽略。Forward、MSAA 或不符合 Lighting GI 要求的 draw 保持原路径，半 GI 还要求至少 24 个片元纹理单元。旧场景缺字段时读为半 GI=false、完整回退=true。

## 显存与下一步

单 deferred batch 的新增 GI 目标按内部像素估算：保留回退约 80 字节/像素，关闭约 48 字节/像素；内部 1920×1080 分别约 158.2 MiB 与 94.9 MiB，不含原 GBuffer、后处理和驱动开销。关闭开关后池内分配可能留待复用，不代表立刻返还显存；视口/图清理时正常释放。

保持画质的后续优化应先评估把拒绝像素紧凑排列后执行原查询，减少散布回退的低利用率；也可研究更保守且低成本的 tile/区域调度。尚未实现或验证，不保证一定加速。当前可用这两个独立开关评估取舍，不默认启用无回退模式。

## 本机证据与复现

- [窗口每阶段 FPS / 分辨率 / 帧时间 CSV](build/gi-cost-check/fps-rounds.csv)，最终 FPS 日志仅使用 fpsonly-s5-1080.log、fpsonly-s5-720.log、fpsonly-s2-720.log。
- GPU 日志：build/gi-cost-check/fallback-sequence-s5.log、fallback-sequence-s2.log。阶段顺序为原、保留、关闭、关闭、保留、原。
- 质量原始数据及统计：build/gi-cost-check/quality-metrics.json、fallback-quality-metrics.json；原图文件名 quality3-s2-arch-*、quality2-s5-*、nofallback-quality-*，扩展名 .rgba32f。
- 临时本机工具：GiCost.java、prepare.ps1、run.ps1、analyze-half.py、analyze-fallback.py。全部位于忽略的 build/gi-cost-check，依赖本机用户资产和 classpath，未承诺跨机器可直接运行。下载的库、用户模型、生成图和日志不随源码提交。
- 仓库可运行回归入口：`gradlew :jme3-examples:halfResolutionProbeGiSmoke`；TestGiVisibilityFiltering 的 `-Dgi.test.halfResolutionCompare=true -Dgi.test.compact=true`。新 API 的用法和恢复步骤见 ViewPort / WorldSettings Javadoc 及 TestHalfResolutionProbeGi。
- 早期默认窗口设置、带 GPU 插桩的窗口 FPS 和未通过完整阶段的测试不纳入最终 FPS 表。

更正此前覆盖率诊断：TestScene2 的 409440 个接收像素对应实际内部 853×480，不能按输出 1280×720 解释成只有 44.43% 内部像素接收 GI。因此此前“两个场景的差异主要是约 2.25 倍 GI 覆盖率”不成立；比较时必须先核对内部渲染尺寸。原始查询计数及 TestScene5 自身消融结果仍可参考。
