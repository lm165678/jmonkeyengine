AMD FidelityFX FSR2 algorithm sources, MIT license retained in each source file.
Official source: https://github.com/GPUOpen-Effects/FidelityFX-FSR2
Official tree: 1680d1edd5c034f88ebbbb793d8b88f8842cf804
OpenGL reference: https://github.com/JuanDiegoMontoya/FidelityFX-FSR2-OpenGL
OpenGL reference tree: f188a0d839665cf110957c1510dfeba4e746aa98
AMD headers/passes are unchanged. Fsr2OpenGLBackend adapts them at runtime:
- FP32 GLSL430; removes Vulkan descriptor sets and separate sampler syntax.
- Assigns OpenGL sampler/image/UBO slots once per linked program.
- Uses signed floating-point prepared color (also corrected by the GL reference)
  and R32F dilated depth consistent with the upstream CPU resource contract.
- Renames wrappers shadowing GLSL bitfield built-ins on NVIDIA's source compiler.
- Ignores generated force16 includes only in this FP32 configuration.
ffx_fsr2_prepare_reactive_pass.glsl is a jMonkeyEngine input adapter, not AMD code.
It sanitizes invalid motion and marks those pixels reactive. Optional custom masks
remain application-owned; this is not automatic transparent-color difference capture.
maximum_bias.bin is little-endian signed short round(table[i]/2*32767), matching
upstream CPU upload. Its original MIT-licensed table is retained alongside it.
