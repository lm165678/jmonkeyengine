## 2026-11-17 managed shadow strength (separate from unresolved GI dark patches)
- User requested shadow darkness control only if comparable engines expose it. Verified Unity Light.shadowStrength and UE5 UDirectionalLightComponent.ShadowAmount in official docs; no blanket claim for every UE4 light/render path.
- Added serialized LightShadowSettings.strength in [0,1], default/missing-field fallback 1. ShadowManager uploads texel33.y; shared managed shader blends 1 with filtered visibility, only for that light's direct contribution. Existing scene defaults and eight-probe GI are unchanged. Strength0 does not disable atlas allocation; use castShadow=false to disable work. Legacy shadow processors retain their own ShadowIntensity API.
- GI capture explicitly overrides strength to 1 in its private viewport: runtime artistic/diagnostic attenuation must not feed unoccluded light into a new bake. Runtime change does not alter saved GI. SDK per-light Shadows group exposes Shadow strength (0..1), with usage/restoration comments. It requires matching new engine upload/shader/API; rebuild/export engine and SDK together, do not patch one runtime jar.
- Validation passed: targeted Java11 core/example compilation, SDK JmeLight compilation; JUnit 3 tests for clone/J3O/default/invalid input and capture isolation. Extended managedShadowSmoke GPU example checks 1/.5/0 in Forward/Deferred/CPU/GPU tiled with Lighting/PBR, GBuffer presence and non-red light/ambient/emission isolation. Lighting red channels 0/127/255, PBR0/40/80; other channels constant. Previous shadow smoke phases also passed. Logs build/shadow-strength-gpu.log and shadow-strength-junit.log. No complete SDK build/UI deployment or source scene changes.
- GI diagnosis remains unresolved: isolated GI contains the dark distribution; direct same-position single-bounce capture is also weak. Cannot conclude runtime probe blending alone causes it, nor rule out capture shadows. Strength is a debugging/art tool, not a claimed GI fix. Other pending GI work remains separate.
- Commit attempt could not execute: elevated shell exited 0xC0000005 before running the prepared isolated staging script. HEAD/index verified unchanged; source and handoff edits remain uncommitted. No push attempted.

## 2026-11-17 exact user camera acceptance still failing
- User supplied exact views, FOV45 near .1 far500: A position(-6.4921,14.0529,-10.2892), direction(-.8539,-.4500,.2615); B position(-1.1240,15.4278,-5.4842), direction(.8688,-.4014,-.2899). Local capture now supports width/height/FOV; captured1152x560, targets=position+direction. These reproduce the marked upper-gallery areas; no more camera clarification needed.
- build/exact-user-1/2.png reproduce dark regions with original bake. build/exact-gi-old/one/three.png isolate GI with a common diagnostic white=.01. One-pass16 still has a broad dark corner; three-pass is smoother/brighter in amplified diagnostics. CRITICAL: build/exact-three-1/2.png at original display gain remain very dark. Therefore previous three-pass candidate is NOT accepted as a fix for user's exposure-like darkness. Do not present boosted diagnostic imagery as the final result or overwrite the original on a claim of complete fix.
- Direct cosine hemisphere capture at same floor y9.5769,z-8: x=-24,-22,-20,-18 red irradiance=4.00e-7,3.23e-7,4.61e-5,5.09e-6; local projected rawSH=6.78e-5,8.05e-5,7.65e-5,5.81e-5. Low single-bounce energy exists in the capture before neighbor blending; negative SH truncation is not the explanation at these points. This uses the existing GPU capture/shadow backend, not an independent ray-traced oracle; does not by itself clear bake-shadow errors. SDK gamma=true confirmed. Harness Filmic ToneMap differs potentially from SDK enabled filter set; retain HDR-off/linear diagnostics.
- No new production algorithm change or claimed complete fix on this continuation. Existing eight-probe policy preserved. Needs further capture-lighting validation or explicitly scoped scene-lighting solution; do not indiscriminately raise GI/exposure or alter Cornell. Original still not overwritten; previous elevated save execution failures remain.

## 2026-11-17 follow-up: static upper-gallery dark regions remain unaccepted
- User supplied three more images; confirms patches persist with a stationary camera. Treat as spatial GI distribution/visibility investigation, not auto-exposure adaptation. Asked for Camera Position/Direction for one marked first/third-image location; exact reproduction still pending. Do not reuse approximate ground-floor views as acceptance for upper-gallery corners.
- Rechecked actual external sponza.j3o: SHA256 remains 87de203066a2fc400df415d63ef8f546b7663aeb1a89d87250a270299fdfdbb8 (old 8x8 distance moments). SDK cluster jme3-core jar now contains m_GiBakeBounce and no giPlaneMoment: unlike the previous handoff, disk SDK has the rollback/bounce shader. This does not inspect an already-running JVM's loaded shader. Do not tell user the disk SDK is definitely still 788.
- New isolated upper-gallery captures: camera (15,15,-8)->(5,11,-8), GI-only fixed white point .1. Replacing every sampled probe irradiance with constant 1 produces continuous illumination at that view (build/upper-unit-field.png), so no all-zero-weight gap there. Original/one/three true-data captures remain dark; uniform-field diagnostic is NOT a production change. Normal shader/source kept unchanged.
- FloorReference directly captures cosine-weighted single-bounce irradiance (8192 hemisphere directions) at x=-15,-5,5,15,z=-8, floor y=9.5469 plus .03. Upward RGB energy is about .0015..0028 in red; very low direct-bounce energy exists independently of neighbor blending at these samples. At actual upper probe positions y=11.512621,z=-10.986698, direct hemisphere integral vs fresh SH does not show a consistent negative-SH truncation explanation (build/probe-direction-reference.log). This does NOT prove the user's marked region is correct; exact camera/region still needed.
- RegionAudit shows the saved model has 12 sponza-geom meshes, Opaque/Dynamic/BlendOff/Triangles; classification was not skipped due to transparent queue. Geometry material/placement hypotheses remain unproven. No new production correction was made on this follow-up; do not claim corner darkening fixed.
- Retried authorized hash-guarded original-path save through elevated exec: again exited 0xC0000005 before executing. Original unchanged; candidate remains build/sponza-fixed-placement3.j3o. Keep all 27-probe options disabled and do not raise exposure/intensity to hide errors. Documentation and isolated diagnostic source/logs updated; no new commit/push.

## 2026-11-17 Sponza regression rollback and optional diffuse bounce baking
- Supersedes the receiver-plane success claims below for engine 788a35774. It reproduced new dark arch facets and floor waves in Sponza. Restored the pre-788 eight-probe merged-moment visibility; no 27-probe switch, no intensity/bias workaround. Compact visibility/cell tests did not catch the actual-scene regression. The old capture harness also had swapped stable/smooth constructor booleans and lacked SDK root CastAndReceive shadows; fixed local harness before comparisons.
- New GiSceneBaker.Settings.bounceCount defaults to 1, range 1..8. Subsequent passes sample only the previous completed field at unit intensity; saved GI and display intensity do not feed back. Bindings, forced parameters, camera/FBO restore per face. Reuse placement/moments/sky visibility; publish only the final pass. Cancellation preserves prior data. Multi-pass checkpoints explicitly unsupported; invalidate restarts all passes. Unshaded stays emissive; capture skips runtime edge fade and double sky/environment attenuation. Custom GI materials require GiBakeBounce support. Volume must cover receivers. Multi-bounce adaptive bricks are explicitly rejected (maxRefinements must be 0); one surface-layout volume remains supported but not GPU-tested here.
- SDK Bake GI panel adds Diffuse bounce passes (default 1 on reopening); unchanged eight-probe runtime setting. Both repositories' changes are local implementation based on engine 788a35774 / SDK 067a2f80. Updated SDK action requires this engine API; do not deploy it against the older engine. No SDK package/export/deployment or push performed. Existing unrelated SDK edits preserved.
- Tests actually passed: Java 11 targeted core/example compilation; targeted SDK action compilation; real GL Lighting and PBR multi-bounce tests (8.728876 -> 10.336334 summed upward irradiance), cancellation/material restoration/settings snapshot/intensity independence/compressed persistence; compact visibility, 65-position cell continuity and 128-phase low-confidence interpolation across four paths/two materials. Entrypoint TestGiMultipleBounces, Gradle giMultipleBouncesSmoke (add -PgiPbr); tests here launched via local Java classpath wrappers, not a full Gradle suite. Cornell TestScene3 same-view current/pre-788 PNG SHA256 both 37f711984cb155cc588aa9b699194c3e026a5ebc1e0b7eba283b0e2d7d835b4e. This is one real view, not universal regression proof.
- Original external scene confirmed E:/MyWorks/JmonkeyProjects/TestJmeNewFeature/assets/Models/sponza/sponza.j3o. Its embedded moments have resolution 8, 975 valid probes / 5 offsets, intensity 2.5, stable=true, wide=false. Re-baked matched original positions/validity with capture64/radiance4096/visibilitySamples64. At resolution8 the wall-foot wedges recur; resolution16 reduces them in the same single-bounce view. This is angular moment texture resolution, NOT an increased runtime probe count. Earlier automatic-placement trial produced 1000 valid probes and is not the delivery candidate.
- Verified candidate build/sponza-fixed-placement3.j3o: three passes, resolution16, original grid/975-valid/5-offsets/intensity/stable/eight-mode. SceneSaveAudit proves geometry, material bytes, lights, local transforms and probe placement unchanged. Independent reload/render passed wall, lion/arch and corridor views. Candidate SHA256 FA6AFB8F446E927C87A1F178AE642252BBE7F8398DA525D8CD48711301A024FF. Single-pass control build/sponza-fixed-placement1.j3o. Diagnostic white-point .1 captures amplify defects only; HDR-off controls also captured. No exposure settings saved/changed.
- User correctly challenged the GI-off inference: the dark side is nearly black without GI, so matching silhouettes establish direct shadowing but CANNOT rule out abnormal GI transitions or prove the reported exposure-like effect persists with GI disabled. Added GI-only controls by disabling runtime direct-light color while retaining the embedded bake, fixed ToneMap white=.03 (diagnostic display gain only), camera (-15,4,-8)->(-5,0,-8). build/gi-hall-indirect-original/one/three.png show the isolated field and additional bounced energy. No hard shadow-shaped GI cutoff observed in this sampled view, but exact user camera is not reconstructed and no universal continuity claim follows. Do not describe the corridor report as explained away or fully fixed.
- External original is STILL UNCHANGED: SHA256 87DE203066A2FC400DF415D63EF8F546B7663AEB1A89D87250A270299FDFDBB8. Authorized original-path save could not execute: normal child process got EPERM on backup copy; elevated exec (PowerShell/cmd, including PTY) repeatedly exited 0xC0000005 before execution. No backup was created. build/save-verified-sponza.cjs and sponza-save-hashes.json prepare a hash-guarded backup/replace if the host execution problem is resolved. Do not claim original saved. Candidate textures still resolve from the original assets root.
- Commit attempt also failed before execution with host 0xC0000005; both repositories remain uncommitted, no index staging or push. Engine HEAD remains 788a3577409fb057b452c1435ef8ba76fd101ecb. Prepared build/commit-gi-fix.cjs stages only related files and an isolated SDK handoff hunk; regenerate that handoff patch if notes change.
- Next: rebuild compatible engine/SDK using normal project workflow to load rollback/UI; inspect user camera views against candidate; finish original-path backup/replace when execution works. Runtime SDK packaged shader may still be 788. Keep strict eight-probe policy. Cross-machine transfer needs external model/assets; ignored build artifacts are not versioned.

# Development handoff — 2026-11-16

## Sponza eight-probe receiver-plane correction
- User explicitly REJECTED enabling 27 probes. Keep default eight probes; do not use quality-mode switching as a fix. The independently created external sponza-gi-quality.j3o was removed after verifying its hash matched our generated file. Original sponza.j3o and its embedded bake were never edited. Ignored build quality-mode artifacts are diagnostic only, not deliverables.
- Current correction translates each of the four moment means to the central receiver ray before the existing variance merge. Uses geometric triangle plane from position derivatives, not normal-map lighting normals; smooth horizon fallback and degenerate-plane fallback retain the original distribution. Eight probes/four texture taps, intensity, bias, wrapped weights, strict/stabilized policy and bake format are unchanged. Additional arithmetic has NOT been GPU-timed. Wide and surface-layout algorithms are unchanged.
- Tests: GPU compact plane illumination and fully blocked probes passed all four render paths/Lighting/PBR; eight-probe 65-position cell continuity passed strict/stabilized modes. Actual Sponza same-view wedges reduced (build/sponza-circles-before.png vs sponza-circles-finalcandidate.png); Cornell TestScene3 contact and backside captures were compared against freshly rendered baseline. No universal no-leak claim: compact moment merging still fails the wide reference's mixed foreground/background checkerboard criterion. New giCompactVisibilitySmoke deliberately documents this limitation and tests only supported compact guarantees.
- No new scene setup, no rebake, no SDK source/API change required. Compatible SDK 067a2f80; engine implementation is the commit containing this entry. Runtime JARs have NOT been exported/deployed, so an already running SDK still uses its packaged shader. Original external model path remains E:/MyWorks/JmonkeyProjects/TestJmeNewFeature/assets/Models/sponza/sponza.j3o.

## Sponza wall-foot color wedges: rejected quality-mode investigation
- User supplied SDK screenshot circles repeated blue/red triangular wedges at the wall/floor junction. Reproduced with camera (-5,5,0) looking at (-5,2,12), original Models/sponza/sponza.j3o. Same-view NormalMap-off capture retains wedges; do not attribute them to normal maps. SDK packaged ProbeSampling.glsllib matches current source byte-for-byte.
- Local experiments replacing eight-probe merged-moment visibility with four receiver-plane PCF taps removed small wedges but introduced stippling; derivative geometric-plane variant introduced black areas. BOTH REJECTED AND REVERTED. Shader matches build/sponza-probe-before.glsllib byte-for-byte. No engine/SDK algorithm change, no Cornell scene or eight-probe default change. Cornell was not rerun in this session; preservation is by unchanged implementation, not a new GPU regression claim.
- Existing per-volume 27-probe quality mode substantially reduces wedges in the captured Sponza view. This is an opt-in mitigation with higher sampling cost, NOT a root fix to the default eight-probe algorithm. Retained original intensity 2.5 and embedded bake; no rebake. Independent build/sponza-gi-quality.j3o exported before transient material binding. Reload verified intensity=2.5, wide=true, embeddedGi=true, then a separate process rendered it successfully (build/sponza-quality-save2.log, sponza-quality-reloaded2.log, sponza-quality-reloaded.png). Texture references still require the user's original asset root.
- Comparison at common intensity 5: build/sponza-circles-before.png, sponza-circles-nonormal.png, sponza-circles-wide.png. Current runner handles empty GI-off sets and saved wide preference. Windows process crashes 0xC0000005 remain intermittent. Next algorithm work must retain eight-probe continuity/occlusion tests plus Cornell and actual Sponza images; do not promote either rejected experiment. Compatible engine/SDK remains 12f5239be / 067a2f80.

## Sponza capture audit (2026-11-16)
- ActualGiCapture loads E:/MyWorks/JmonkeyProjects/TestJmeNewFeature/assets/Models/sponza/sponza.j3o when capture.scene=Models/sponza/sponza.j3o. Original asset unchanged. Saved volume intensity is 2.5 (verified GPU capture build/sponza-saved-settings.log); previous capture runner hard-coded 5 and additionally applied ToneMap/FXAA. Its intensity1/intensity5/nogi images were invalid controls because those properties were not read.
- Local build/gi-quality-runner/ActualGiCapture.java now reads capture.intensity (default saved intensity) and capture.noGi. Saved-settings and intensity5 GPU captures passed. No normal-map hypothesis or GI algorithm fix has been validated; Cornell baseline unchanged. Engine/SDK implementation remains 12f5239be / 067a2f80.
- Attempted optional scene export/readback in the local runner, but repeated host process exits 0xC0000005 blocked verification. Do NOT claim a saved screenshot scene or new bake was delivered. Resume by compiling the runner, exporting a separate file with embedded GI, checking RELOADED output, then running GI-off and normal-map-off controls. Original user screenshot is not present in this chat; exact view matching remains unverified.

## 2026-11-16 receiver-plane PCF correction
- Managed soft shadows now project the receiver tangent plane analytically through the selected light matrix (including perspective w) and adjust reference depth per actual clamped atlas texel center. This avoids derivatives in divergent light/cascade branches. PCF tap counts remain 16/36; extra arithmetic is not free and has not been GPU-timed. Hard mode and legacy wrapper retain previous comparisons.
- Safety: degenerate/near-singular projections fall back to zero gradient; gradient is bounded to 0.01 normalized depth per texel and tap correction to +/-0.02. Uses supplied shading normal, so highly perturbed normal maps remain an approximation. Existing depth/normal bias and serialized settings are unchanged; no rebake, SDK API change or additional setup needed.
- Actual TestScene3 ceiling captures: build/shadow-plane-ceiling.png (saved 0.001 depth/0.01 normal), shadow-plane-zero.png (zero remains precision-sensitive), shadow-plane-small.png (0.00001 depth/zero normal, no prior obvious slope stripes). The residual thin wall/ceiling seam remains; this change is NOT claimed to eliminate contact leaks. No contact-shadow feature or cascade changes implemented.
- GPU validation: managedShadowFilterSmoke --args=--plane passed positive/negative slopes and both radii including simultaneous uncorrected control (build/shadow-plane-control.log); managedShadowSmoke passed all four paths/Lighting/PBR. Actual small-bias baseline build/shadow-plane-small-before.png still shows slope stripes; corrected build/shadow-plane-small.png does not. Compatible SDK remains 067a2f80; engine implementation is the commit containing this entry. No deployment/push.

## 2026-11-16 managed shadow contact seams
- Root cause reproduced in actual `Scenes/TestScene3.j3o`: material PreShadow `PolyOffset 5 3` overrode the renderer's state, so setting receiver depth/normal bias to zero did not remove the broad wall/ceiling leak. Zero caster offset with zero receiver bias causes acne, so do not globally zero receiver defaults.
- Managed shadow casters now explicitly own raster state (two-sided, zero polygon offset); existing receiver depth/normal settings remain effective. Legacy standalone shadow processors retain technique-owned polygon offset. RenderManager's new opt-in forced-state priority is scoped and restored with light filter/state in finally. No additional shader taps or GI changes, no rebake required.
- Actual scene before/after: `build/shadow-ceiling-before.png` / `build/shadow-ceiling-fixed.png`, same camera and saved receiver bias 0.001/0.01. Broad white seams substantially reduced; fine filtered edges remain, not a guarantee of zero leaks. Normals, shadows-off, zero-bias and zero-caster-offset controls isolated shadow raster bias. Models were not edited.
- Validation: core compilation and ShadowManagerTest passed; `:jme3-examples:managedShadowSmoke` passed all four paths with Lighting/PBR and GBuffer checks. ForcedTechniqueStateTest passed via direct JUnitCore (`build/shadow-junit.log`), checking legacy/override/restoration/null-fallback behavior; Gradle's test worker had first crashed with the known host NTSTATUS 0xC0000005. SDK API unchanged, compatible with SDK 067a2f80; engine implementation is the commit containing this section. No SDK deployment or push.

## 2026-11-16 eight-probe default restoration
- Default regular-grid path is eight probes with wrapped directional weights and smooth signed-plane attenuation from -0.2 to +0.2 minimum cell spacing. Avoids the hard positive-hemisphere rejection that starved eight-corner support. Four explicit moment taps merge variance using the parallel-axis formula; no minimum visibility in strict mode. Stabilized mode remains opt-in.
- Optional `GiProbeVolume.setWideProbeSampling(true)` retains 27-probe/16-visibility-tap reference. Defaults false including old J3O. Persistence, automatic SceneServices, streamer page and fallback atlas, generated refinements and SDK preview/Properties are wired. Free surface layouts retain their independent radial behavior (this is not an eight-probe mode).
- Tested: core/examples compilation; GiWideSamplingTest and GiSmoothBlendingTest (Gradle success); SDK changed sources targeted javac passed, NOT a full SDK build/UI run. Eight-mode grid continuity passed all four paths/two materials at 65 sample positions; wide-mode continuity and strict visibility suites passed before the final eight-only attenuation adjustment. Actual TestScene3 captures checked corridor and small cube backside; no universal no-leak guarantee.
- GPU timer query around scene update/render, 1920x1080, 60 warmup + 180 measured frames: prior fixed 27x16 shader 1.266 ms, final 8x4 0.562 ms on this machine/view. This is not whole-application FPS nor a large-world benchmark; small 640 viewport measurements were noisy and inverted ordering once. Local artifacts build/gi-eight-final65.log, gi-eight-unit.log, gi-eight-finalperf.log, gi-baseline-1080.log, gi-eight-back2.png.
- Compatible implementation pair: engine 5b7735e21 / SDK 067a2f80; SDK requires the new wide-sampling API. No JAR export/deployment or push performed. Known remaining limits: filtered moments can soften visibility/light leaks; no guarantee for all thin-wall cases. Do not describe this as exact NVIDIA reproduction: continuous signed-plane attenuation and strict zero-visibility handling differ.

## 渲染质量升级：下一阶段入口
- 已将用户讨论的画质升级计划写入 [RENDERING_QUALITY_ROADMAP.md](RENDERING_QUALITY_ROADMAP.md)。另一台电脑先读本文、AGENTS.md 和该路线图。
- 建议顺序：统一 HDR/曝光/Tonemap → 公共时序基础 → Bloom/AO/阴影/IBL → 间接光与反射/SDF DDGI → 摄影效果。当前只新增方案文档，尚未实现这些升级。
- 引擎性能修复提交 `42353672aa5f2b3d64b021c56426afbcc1aed068` 已推送且远程核实；下文当时的“local only/preparing”记录是历史状态。SDK 未修改，gradle.properties 中本机代理未提交。
- 当前仍需关注 Probe Volume 区域混合断言失败和结构变更触发全量服务发现的限制。路线图包含复现命令、验收要求和跨机器接续步骤。

## 2026-11-16 review and engine submission
- Fixed review finding: disabling SceneServicesState now closes its discovery catalog, releasing detached volumes/materials/controls and removing the listener. Re-enabling rediscovers the current scene. Added lifecycle regression; all 6 targeted catalog/shadow tests passed (`.gradle/scene-cache-commit-check.log`).
- Preparing this engine change for `origin/dev_feature_lightprobe` at the user's request. SDK is excluded. Project-local proxy values in `gradle.properties` remain uncommitted. Earlier "local only" notes below describe the state at validation time; verify remote commit before claiming delivery.

## 2026-11-16 GPU benchmark automatic-service CPU regression
- Reproduced `TestGpuDrivenPerformance` with 100,000 models / 266,665 geometries / 1,000 movers on RTX 5060 Ti. JFR samples identified repeated directional-shadow traversal plus per-frame GI/physics discovery. These costs occur even without GI payloads or shadow casters.
- Engine fix: `ShadowManager` caches caster eligibility alongside its light catalog and schedules no maps when no geometry casts. Attachment/detachment/rendering changes (including inherited ShadowMode changes) invalidate it; existing budgets remain enforced when casters exist.
- Engine fix: internal `SceneServiceCatalog` shares cached GI-volume/material/control membership between automatic GI and physics services. Normal scene structural/rendering changes trigger discovery; transforms do not. Existing volumes' payloads/positions/preferences and registered physics ownership remain checked each frame. Structural mutations currently trigger a full rediscovery, not subtree-only streaming updates.
- No benchmark object counts, culling settings, material paths or service enable switches were reduced. Added caller-facing usage comments. Unit tests cover no-rescan movement, attachment/detachment, control removal, material replacement and inherited shadow-mode invalidation (5 tests passed).
- Same 120-frame/JFR configuration: before static/moving means 105.684/108.741 ms; after 1.054/4.939 ms. Final longer run (600 frames per interval, no JFR) measured 1.056/4.569 ms; `GPU_PERFORMANCE_PASS`, triangle statistics check and PBR terrain Forward/Deferred/CPU/GPU round-trip checks passed. Logs: `.gradle/gpu-performance-before.log`, `gpu-performance-after.log`, `gpu-performance-final.log` (local only). Timings are machine/run dependent.
- Existing issue isolated: `automaticSceneServicesSmoke` fails regional blending with [102,0] / [1,101] / [0,102]. Re-ran with the three changed existing engine classes temporarily restored to HEAD: identical failure (`.gradle/gi-services-baseline.log`). Restored optimized sources and rebuilt afterward. Do not claim the GI smoke passed; investigate its regional blending expectation/implementation separately.
- Local only, no commit/push. Unrelated proxy/IDE changes preserved.

## 2026-11-16 PBR terrain render-path example shadow fix
- `TestPBRTerrainRenderPath` creates 1,000 point lights for lighting throughput. Since `Light.castShadow` defaults to true, these unintentionally requested managed shadows and triggered the 64-active-light guard.
- The example now explicitly calls `setCastShadow(false)` on its benchmark point lights; illumination, light-count controls and directional-light preferences remain unchanged. Added step-by-step shadow opt-in/restoration comments. Engine shadow budget/error policy is unchanged.
- Verified on this machine: `gradlew :jme3-examples:renderPathSmoke -PrenderPathExample=TestPBRTerrainRenderPath` completed with `RENDERPATH PASS` and `BUILD SUCCESSFUL`; real offscreen GL checks cover Forward, Deferred, CPU/GPU tiled paths and Forward round trips. This does not validate 1,000 simultaneous shadow-casting lights.
- Changes are local, not committed or pushed. Existing unrelated local configuration changes are preserved.

这是开发状态摘要，不是完整聊天记录。以源码、测试和实际运行结果为准。

## 配套仓库与恢复
- Engine fork: https://github.com/lm165678/jmonkeyengine ，分支 `dev_feature_lightprobe`。
- SDK fork: https://github.com/lm165678/jmesdk ，分支 `master`。
- 配套实现基线：engine `1fc6e64e3a5874e004247759f76151c05606c66e`；SDK `ebf74c16cce9d4b11542c845107c6462d7632ef1`。
- 2026-11-16 已通过 GitHub API 确认两个实现提交都在对应 fork 分支；之前 SDK 推送失败的记录已过时。本文档的提交在基线之后，不改变实现。
- 新电脑无需交接 ZIP：克隆两仓库并保持相邻目录，再读各自 AGENTS.md 和 DEV_HANDOFF.md。

```powershell
git clone -b dev_feature_lightprobe https://github.com/lm165678/jmonkeyengine.git JMESources
git clone -b master https://github.com/lm165678/jmesdk.git jmesdk
```

已有工作区先检查 `git status`，处理本地修改再 `git pull --ff-only`。不要强制覆盖未提交工作。构建依赖需要重新下载，GitHub 登录在各电脑单独设置。
外部游戏项目 TestJmeNewFeature 和 buster_drone 模型不在这两个仓库，需另行迁移。生成的 release 运行库、模板解压资源、日志和构建 ZIP 不作为上下文提交。

## 下一步与切换流程
- 动画编辑器最后一轮功能修复和 SDK 构建已完成；按用户新反馈继续迭代，不重做已实现功能。
- 新电脑先适配脚本 JDK 路径、导出本地引擎，再构建 SDK 和运行相应测试。
- 原电脑 Git/终端随机崩溃尚未定位；不要宣称已修好，也不要把凭据读取失败直接归因于缺少账户权限。
- 每次结束开发更新本文：变更、测试、待办、配套实现版本，再 commit/push。另一台电脑 pull 后让助手读取。文档不会自动同步。
- 用户偏好直接执行已授权工作并测试；推送目标是自己的 fork，不是 upstream。不在文档或聊天中存放凭据。

当前文件属于 **引擎** 仓库。下文保留跨仓库关联信息，联动修改时同步检查另一仓库交接文档。

## 用户目标
在 SDK 中实现类似 Unity 动画状态机的可视化编辑器；统一 .j3o，不拆分 .j3oskinned。Java 游戏逻辑提供参数，由动画图控制状态。V1 包含参数、状态机、定时过渡、1D 混合、实时预览。分层遮罩、事件和复杂打断尚未实现。

## 已实现的引擎功能
- jme3-core/src/main/java/com/jme3/anim/graph/AnimationGraph.java：版本化 String 用户数据 jme.animationGraph.v1；FLOAT/BOOLEAN/TRIGGER 参数，Clip/1D 状态，转换条件、淡入淡出秒数、退出时间。
- AnimationGraphPlayer.java：AnimComposer 默认层运行，Transform/Morph 动画采样，1D 混合、触发器消费，close 恢复原状态；过渡不可打断。
- jme3-examples/src/main/java/jme3test/anim/TestAnimationGraph.java 为示例。
- jme3-desktop AwtPanel：修复未初始化 RenderManager/FrameBuffer 就收到帧回调造成 rm=null 的崩溃。
- 本次引擎提交还有 GI 探针、SkyLight、托管阴影、SceneServices、渲染整合等原先未提交代码和测试；这些更早开发的完整聊天不可见，具体以源码/Javadoc/测试为准。

## SDK 实现位置
jme3-core/src/com/jme3/gde/core/assets/：
- AnimationEditorTopComponent.java：图、表格、撤销、独立原生预览。
- AnimationEditorSupport.java：打开路由、完整模型克隆、保存。
- AnimationGraphComments.java：备注元数据 jme.sdk.animationGraph.comments.v1。
- ModelConversionAnalysis/Options/Preparation.java：转换预检和选项。

## 编辑器行为与操作
1. 文件和场景节点右键 Open in Animation Editor；也可右键在场景编辑器打开。旧文件缺少偏好时默认场景；AUTO 按 AnimComposer 片段识别，SCENE/ANIMATION 显式覆盖。骨骼蒙皮和存在动画片段分开识别；旧 AnimControl 模型需迁移。
2. 转换含动画/蒙皮/morph 的模型，默认关闭 Bake SDF 和表面属性。不支持的表面捕获跳过，不让整个转换失败。
3. 状态左右蓝色圆点均可拖线，方向是起点到目标。双向转换分别建立；相向端点走线，曲线分开。点击箭头定位 Transitions 行，悬停高亮，右键 Delete transition 删除。
4. 框选，Shift 增选，Delete 删除状态并清理关联连线。删除入口状态后选首个剩余状态。空图允许草稿，但必须有入口才能预览/保存。删除状态不删除源动画片段。
5. Ctrl+Z 撤销；Ctrl+Y 或 Ctrl+Shift+Z 重做，支持创建/删除、连线、移动、备注文字修改。
6. 框选按 C 建备注，Ctrl+Enter 确认，双击编辑；拖标题移动完全包含的状态，右下角调框大小；删除备注保留状态。备注随 J3O 保存。
7. Shift+滚轮围绕鼠标缩放 25%–300%；中键拖动平移。仅影响视图，不改保存坐标或撤销记录。
8. Apply preview 显示在右侧上方。主场景线程克隆完整模型，用子节点索引定位选中 Composer 的副本，避免同名节点和兄弟几何体丢失；预览线程安装模型，其余 Composer 禁用。拖动旋转，滚轮缩放。
9. 预览使用 SDK pbrenv.j3o 环境探针，使金属材质可见。预览副本移除非动画、蒙皮、morph 控制器；灯光不写回原模型。
10. Save J3O 先完整序列化后尝试原子替换。Windows 文件允许写但拒绝 rename 时，备份后通过 NetBeans 锁定流写入；失败恢复原字节，恢复失败保留备份并报告路径。流回退不保证断电原子性。

## 验证和构建
SDK tools/verify-animation-editor.ps1 验证无界面 Swing 交互、状态图运行、保存重载、GLTF 转换预检。加 -Preview 验证 OpenGL/AWT 原生预览。可选 -AssetRoot 路径 -Model 相对路径，只读测试指定模型。
已通过：双端拖线、悬停/删除、撤销重做、框选/备注、保存恢复、缩放平移和变换后节点拖动。Windows NOSHARE_DELETE reader 验证禁止 rename 时保存成功，模拟部分写入后异常验证原文件恢复与 dirty 状态保留。
原生测试通过 108 关节模型、嵌套 Composer 与兄弟几何体完整预览。只读加载用户 buster_drone.j3o，选择 Turbine_L，获得完整模型截图；未修改用户原文件。
最近 SDK buildSdk 成功，耗时 1m51s。这不代表完整引擎所有其他功能都经过全面验证。

旧电脑命令参考，先适配 JAVA_HOME：
```powershell
$env:JAVA_HOME='C:/path/to/jdk-25'
# 在 SDK 目录，先按 build-local-engine-sdk.bat 导出引擎
./gradlew.bat -PuseLocalEngine=true -PlocalEngineRepository=../JMESources/build/sdk-engine-repo buildSdk
./tools/verify-animation-editor.ps1 -Preview
```
测试脚本也硬编码旧 JDK 目录，新电脑要适配。旧电脑 python 默认 Python 2，操作脚本需 py -3。先阅读仓库 AGENTS.md；公共 API 要更新 Javadoc，包含中文步骤用例，Java/GLSL 四空格，不压缩控制流。不要回滚其他用户修改。

## 当前 Git/Windows 故障
- Git 2.34.1.windows.1 随机崩溃：cmd/git.exe --version 10 次失败 1 次，mingw64/bin/git.exe 10 次失败 3 次。退出码 -1073741819 = 0xC0000005。
- Git Bash 报 dofork child died unexpectedly / 0xC0000005 / Resource temporarily unavailable；用户还报告 Git 终端无法关闭，该问题尚未处理。
- 可靠性报告为 StackHash_0000、PCH_84，没有可确定故障 DLL。不能据此认定注册表损坏、安全软件或内存故障。PowerShell 查询事件日志也曾同码崩溃。
- 临时 MinGit 2.47.1 尝试过，未做充分重复对比。凭据管理器读取失败可能是 Git 崩溃后果，不可直接认定没有账户权限。
- 未清理注册表、关闭安全软件或重装系统。下一步是稳定新版对比或捕获崩溃转储。新电脑可能不存在此问题。


## 2026-11-16 IDEA 2026 导入配置修复
- 本机未跟踪的 .idea/gradle.xml 原先指定 gradleJvm=11，与 Gradle 9.8 不兼容；已改为 #JAVA_HOME，本机 JAVA_HOME=D:/MyInstalls/JDK17。
- IDEA Gradle 缓存改为工程下 .gradle-user-home（已加入 .gitignore），避免当前进程默认 C:/.gradle 不可写。其他电脑可使用正常的用户缓存路径。
- 保留 Gradle 9.8 和 Adoptium JDK 25 编译工具链；后者由 Foojay 自动下载，主引擎仍生成 Java 11 字节码。
- 恢复步骤：重新打开工程，Gradle 设置选择 Wrapper、Gradle JVM 选择 JAVA_HOME（JDK 17 或更高），然后 Reload All Gradle Projects；不要再选择 JDK 11。首次同步需要下载 Gradle、插件、依赖和 JDK 25。
- 验证：命令行 help 已进入 Gradle 9.8 下载阶段，但解除执行沙箱限制后仍发生网络读取超时（services.gradle.org，10 秒）；尚未完成 Gradle 配置或 IDEA 同步验证。
- 本次仅本机 IDE 配置和交接/忽略规则调整，engine/SDK 配套实现基线不变；未提交或推送。

## 2026-11-17 新增待办：大世界表面卡片性能
- 用户要求将磁盘分块异步读取、CPU 缓存淘汰、跨实例共享、距离相关精度与预取，以及行走/高速飞行/传送/密集区域性能测试加入待办；详细检查项见 RENDERING_QUALITY_ROADMAP.md 末尾。
- 后续继续大世界或表面卡片性能工作时提醒用户。仅会话交接提醒，未创建定时通知；当前无可用 automation_update 工具。
- 本次仅追加计划文档，未实现、构建、测试、提交或推送；已有实现及兼容版本记录不变，无关工作区变更保留。

## 2026-11-17 documentation entry points
- Both engine and SDK AGENTS.md now require startup reading of their handoff and the engine rendering roadmap, including on machine switches. Relevant backlog reminders are required; plans do not imply implementation.
- Documentation only; implementation baselines unchanged. No build, commit or push.

