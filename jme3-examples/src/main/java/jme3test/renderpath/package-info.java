/**
 * Render-path examples ported from JoyClm/jme-next, retaining their original
 * scenes and controls. RenderPathHelper cycles Forward, Deferred, CPU tiled and
 * GPU tiled paths. The common base requests an OpenGL 4.3 context for interactive
 * GPU testing; CPU-only applications can request OpenGL 3.3 instead.
 *
 * Run one scene with {@code :jme3-examples:renderPathSmoke
 * -PrenderPathExample=TestDeferredPBRShading}, or all scenes with
 * {@code :jme3-examples:renderPathSuite}. Smoke checks use real offscreen OpenGL,
 * wait for probe baking, freeze scene time, compare backend pixels and verify
 * Forward round trips and resource recreation. Images are retained under
 * {@code jme3-examples/build/renderpath-tests}.
 *
 * The port uses the current engine's managed per-viewport API rather than the
 * reference's shared framegraph implementation. TestDeferredFeatures and
 * TestRenderPathIsolation add regressions for the shared material surface
 * contract, local light scopes, custom model registration and viewport teardown.
 */
package jme3test.renderpath;
