# CodeBuddy 工程上下文记录（jmonkeyengine）

本文件由 CodeBuddy 维护，用于记录 CodeBuddy 在该工程中的代码修改上下文。
它不替代 `AGENTS.md`、`DEV_HANDOFF.md` 或 `RENDERING_QUALITY_ROADMAP.md`；那些文档由 GPT6 记录，仍作为历史交接参考。
最终判断以当前源码、测试和实际运行结果为准。
本文件用于随 fork 仓库一起提交，不保存凭据、私钥或令牌。

## 初始化记录（2026-11-17）

- 已读取 `AGENTS.md`、`DEV_HANDOFF.md`、`RENDERING_QUALITY_ROADMAP.md`，以及相邻的 `jmesdk/AGENTS.md`、`jmesdk/DEV_HANDOFF.md`。
- 本轮没有代码修改、构建、测试、提交或推送；仅创建本文件，使其纳入 fork 仓库版本管理。
- 交接文档记录的配套源码快照为 engine `4ee85daa4`、SDK `e38d71c5`。这不是新验证过的二进制版本，不附加额外运行结论。
- 两个工程原本都没有 `code_buddy_agents.md`。

## 恢复开发时的提醒

- 继续开发前按 `AGENTS.md` 的顺序读取交接文档，再核对当前源码。
- Sponza GI 暗区问题仍未解决；精确相机视角已确认，不接受放大增益诊断图作为最终修复。
- 大世界表面卡片流送与性能、渲染阶段一 HDR/曝光/Tonemap 仍是待办；计划不等于已实现。
- `automaticSceneServicesSmoke` 区域混合断言失败是已知问题，不能列为通过项。
- 托管阴影强度已加入源码，但完整 SDK 构建、部署与提交状态必须以实际验证为准。

## CodeBuddy 修改代码时的规则

- 保留无关工作区改动，不覆盖用户或 GPT6 的既有交接内容。
- 公共 API、配置或渲染路径变化时，按 `AGENTS.md` 更新源码 Javadoc/注释，并给出分步骤中文用法示例。
- 新渲染特性在 `jme3-examples` 提供可运行示例，记录入口、能力要求、输入控制和自动验证命令。
- Java/GLSL 使用四空格缩进，控制流加大括号；GLSL 注释保持 ASCII 兼容，中文说明放在对应 Java 示例注释中。
- 只记录实际执行过的构建与测试；未运行就写明“未运行”，不宣称通过。
- 提交或推送成功必须经过验证；不在交接文件中保存任何凭据。
- 每次有意义的代码修改结束前更新本文件和 `DEV_HANDOFF.md`，区分已测试行为、限制和下一步。

## 2026-11-17 CodeBuddy 渲染异常修复

- `RenderManager.renderGeometry`、`RenderQueue.renderQueue`、`RenderQueue.renderShadowQueue` 改为 `try/finally` 配对 `popDebugGroup`。目的：材质/几何渲染异常后不再遗留嵌套调试组，避免后续帧在 `glPushDebugGroup` 报 `Internal stack overflow (1283)` 并掩盖原始错误。
- 新增 `RenderDebugGroupTest`，覆盖普通队列和阴影队列的几何渲染失败路径。
- 验证结果：`RenderDebugGroupTest` 2/2、`FrameGraphTest` 22/22，JUnit XML 均为 failures=0、errors=0。Gradle 在测试完成后因 problems-report `TimeoutException` 报告 BUILD FAILED，因此不能声称整体构建成功。
- 已将 `jme3-core-3.10.0-local-SNAPSHOT.jar` 复制到 SDK 的 release 和 build/cluster 运行目录。尚未在 SDK 内重新打开用户 J3O 做原生 OpenGL 验证；若还有其他材质错误，现在应显示原始错误而非二次栈溢出。
- 未提交、未推送。
