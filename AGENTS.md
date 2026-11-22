# Usage documentation

## Cross-machine handoff

- Startup reading order, including after switching machines: this file ->
  `DEV_HANDOFF.md` -> `RENDERING_QUALITY_ROADMAP.md`. The roadmap contains
  pending rendering and large-world surface-card streaming/performance work.
- For SDK work, also read the sibling SDK files below. Its `DEV_HANDOFF.md`
  contains the Lemur/dual-GUI and coding/scene Agent backlog. Remind the user of
  relevant pending items when resuming that area. Plans are not implemented
  features, and a backlog entry alone does not authorize implementation.

- Read `DEV_HANDOFF.md` before continuing development, and read the sibling
  `../jmesdk/AGENTS.md` and `../jmesdk/DEV_HANDOFF.md` for SDK integration work.
- Keep this file for long-lived rules. Update `DEV_HANDOFF.md` when work changes
  the implementation, validation results, remaining issues or next steps, and
  before handing work to another machine. Distinguish tested behavior from plans.
- Record compatible engine/SDK implementation revisions; documentation-only
  commits may follow those revisions. Do not claim a push succeeded until verified.
- Commit handoff documents with the related work. Never store credentials,
  private keys or access tokens in them. Preserve unrelated working-tree changes.
- For new desktop JNI bindings, follow the Bullet distribution model. Build
  per-platform binaries ahead of time, bundle the supported OS and architecture
  variants and licenses, and select the bundled library at runtime. Verify
  completeness at release packaging and record target-specific test coverage.

## Source documentation

- When adding or changing public APIs, configuration options, or rendering-path
  behavior, update the relevant source comments/Javadoc in the same change.
- Include a complete usage example and explain relevant defaults, fallback behavior,
  switching/restoration steps, and thread/lifecycle requirements. Keep examples
  consistent with the actual implementation.
- For each new feature, include step-by-step usage code in source comments, with
  Chinese explanations such as "第一步：创建 FrameGraph". API descriptions and
  automated tests do not replace these commented usage examples.
- For internal implementation changes, document any affected caller-facing usage;
  do not add redundant examples to unrelated private methods.
- Follow the existing English Javadoc style in engine source files.
- New rendering features must include runnable examples in jme3-examples. Document
  the entry point, required capabilities, key APIs, input controls and automated
  verification command in the example's class Javadoc. Migrate affected existing
  examples when an integration API changes; do not leave obsolete manual setup.
- Prefer material-owned shader declarations in .j3md for application-facing usage.
  Keep explicit source loading/ID registration examples clearly marked as low-level
  extensions, not as the default way to create a Material.

- Keep independent statements on separate lines. Expand method/control-flow bodies,
  use braces for new conditionals/loops, and format Java/GLSL with four-space
  indentation. Do not compress multiple operations into one line for brevity.
- Keep GBuffer storage names neutral (gbuffer0..4). Document channel meanings in
  each model's writer/decoder; shared code may assume only the shadingId header
  and depth contract, not a universal normal/specular/ambient layout.
- Keep GLSL source/comments ASCII-compatible with the LWJGL 2 upload path.
  Put detailed Chinese shader usage/call-chain explanations in the corresponding
  Java example comments; retain equivalent English explanations in shader files.

## Comment style

- Match the surrounding author style. Keep comments concise and focused on purpose, non-obvious reasons and constraints; avoid template-like narration and redundant descriptions.
