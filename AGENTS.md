# Usage documentation

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
