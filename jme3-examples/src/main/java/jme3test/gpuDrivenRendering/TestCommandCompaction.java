package jme3test.gpuDrivenRendering;

/**
 * GPU command compaction benchmark with 300,000 Tank/Boat/Buggy models and
 * 1,000 moving models. Entry: TestCommandCompaction.main. Requires OpenGL 4.3;
 * ARB_indirect_parameters enables actual GPU-count submission. Unsupported
 * hardware falls back to fixed-count MDI. No CPU count readback during timing.
 * Run gradlew :jme3-examples:gpuCompact; automated verification:
 * gradlew :jme3-examples:gpuCompactCheck (add -PgpuDeferred for Deferred).
 * B toggles compaction, M motion, Space GPU Scene; drag+WASD moves the camera.
 * Compares static/moving images and visible triangles, verifies fewer commands,
 * reports timings without imposing hardware-dependent FPS thresholds.
 * <pre>{@code
 * // 第一步：照常加载模型和材质，创建并安装 GPU Scene 管线。
 * GpuScenePipeline pipeline = new GpuScenePipeline(assetManager, models);
 * renderManager.setPipeline(viewPort, pipeline);
 * pipeline.setEnabled(true);
 * // 第二步：开启 MDI 和兼容网格打包，然后压缩零实例命令。
 * pipeline.setMultiDrawIndirect(true);
 * pipeline.setMeshMerging(true);
 * pipeline.setCommandCompaction(true);
 * // 第三步：在渲染线程关闭作对照；不支持 GPU 计数时自动回退。
 * pipeline.setCommandCompaction(false);
 * // 第四步：测试结束移除管线，由引擎释放专用缓冲。
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 */
public final class TestCommandCompaction {
    private TestCommandCompaction() {
    }

    public static void main(String[] args) {
        String[] options = java.util.Arrays.copyOf(args, args.length + 1);
        options[args.length] = "--compaction";
        GpuDrivenFeatureBenchmark.launch(false, options);
    }
}
