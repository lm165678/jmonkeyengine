package jme3test.gpuDrivenRendering;

/**
 * Independent Bindless benchmark: 300,000 real Tank/Boat/Buggy model roots,
 * 32 material variants per model, Lighting/PBRLighting/Unshaded, shared LOD meshes
 * and 1,000 optional moving models. Requires OpenGL 4.3; Bindless additionally
 * requires ARB_bindless_texture and falls back to texture units when unavailable.
 * Uses an 8 GiB heap via Gradle. Entry: TestBindlessRendering.main.
 * Run: gradlew :jme3-examples:gpuBindless; verify: gradlew :jme3-examples:gpuBindlessCheck.
 * Options: --count=300000 (minimum 100000), --frames=180 (minimum 120),
 * --benchmark, --deferred. B toggles Bindless, M toggles motion, Space toggles
 * GPU Scene, drag+WASD moves the camera. Hi-Z is off to isolate the measured feature.
 * Distance=600, screen-size=2 pixels, LOD=80/30/10/3 pixels. This is a resident-world
 * test, not 300,000 simultaneously visible objects. GPU geometry counts are reported.
 * Automated phases compare off/on at identical static and deterministic moving
 * poses, warm up 60 frames after each switch, compare images and visible work,
 * then report frame mean/p50/p95 and draw submissions. Readbacks occur only after
 * measurement intervals. Draw statistics/readbacks use a separate diagnostic
 * frame after each timed interval. Background throttling is disabled for this
 * benchmark, so losing focus does not impose the LWJGL 20 FPS background cap. FPS thresholds are deliberately machine-independent.
 * MDI combines compatible LOD commands within a mesh/material/light group;
 * it does not merge incompatible materials or compact zero-instance commands.
 * Bindless preserves material shading and owns bounded immutable texture snapshots;
 * HUD reports their extra memory. No source assets are overwritten.
 * <pre>{@code
 * // 第一步：照常加载模型和 j3md 材质，共享网格和材质构建数十万实例。
 * GpuScenePipeline pipeline = new GpuScenePipeline(assetManager, models);
 * renderManager.setPipeline(viewPort, pipeline);
 * pipeline.setEnabled(true);
 * pipeline.setLodThresholds(80, 30, 10, 3);
 * // 第二步：独立开启 Bindless，不改变已有材质/着色路径。
 * pipeline.setBindlessTextures(true);
 * // 第三步：同相机、同对象变换下关闭作对照；切换会重建，先预热再计时。
 * pipeline.setBindlessTextures(false);
 * // 第四步：调用发生在渲染线程；移除管线由引擎释放资源，不手工删除 GL 对象。
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class TestBindlessRendering {
    private TestBindlessRendering() {
    }

    public static void main(String[] args) {
        GpuDrivenFeatureBenchmark.launch(true, args);
    }
}
