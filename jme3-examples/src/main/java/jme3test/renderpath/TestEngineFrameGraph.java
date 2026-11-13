package jme3test.renderpath;

import com.jme3.post.FilterPostProcessor;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.framegraph.FrameGraph;

/**
 * 引擎整帧 FG 使用案例：复用已有坦克/地面及三个方向光阴影 Filter 场景，
 * 再加入 Bloom 和 FXAA。SPACE 切换 Forward/Deferred/CPU tiled/GPU tiled。
 * 需要 OpenGL 4.3 才能 GPU 剔除；CPU 路径需要 OpenGL 3.3。
 * 无需给 ViewPort 安装额外 FrameGraphPipeline，整帧调度已由引擎自动接管。
 *
 * <p>每个 ViewPort 只有一个引擎主图：阴影、Deferred、Filter 将实际 Pass 注册到主图。
 * 队列和前序回调确定后，规划节点展开本帧需要的 Pass；不再执行模块子图。
 * 旧 Filter/阴影目标仍由原对象拥有，以导入资源参与主图调度。
 * 应用只通过正常 API 添加/移除效果，不能手动执行或关闭引擎正在使用的图。</p>
 *
 * <p>自动验证：gradlew :jme3-examples:renderPathSmoke
 * -PrenderPathExample=TestEngineFrameGraph；验证 CPU/GPU 像素与 Forward 往返。
 * simpleRender 中同时检查整帧图和后处理图确实执行了预期阶段。</p>
 */
public class TestEngineFrameGraph extends TestDeferredShadingPathShadow {
    private FilterPostProcessor post;
    private int frames;

    @Override
    public void simpleInitApp() {
        // 第一步：照常创建场景和阴影。父例在 ViewPort 上添加三个 ShadowFilter。
        // 阴影贴图的每个级联/面现在由 AbstractShadowRenderer 注册到主图。
        super.simpleInitApp();

        // 第二步：使用现有 FPP 接口添加效果，无需手动分配 FrameBuffer 或组装图。
        // 引擎每帧按 Filter 顺序构建节点，Bloom 的内部 Pass 也会出现在同一个主图中。
        for (SceneProcessor processor : viewPort.getProcessors().getArray()) {
            if (processor instanceof FilterPostProcessor) {
                post = (FilterPostProcessor) processor;
                boolean prepasses = Boolean.getBoolean("framegraph.prepasses");
                post.addFilter(new BloomFilter(prepasses
                    ? BloomFilter.GlowMode.SceneAndObjects : BloomFilter.GlowMode.Scene));
                // 可选：-Dframegraph.prepasses=true 演示额外几何预绘制也进入主图。
                // engineFrameGraphSmoke 自动启用此项，验证 Glow/法线 Pass 保留队列。
                if (prepasses) {
                    com.jme3.post.ssao.SSAOFilter ssao = new com.jme3.post.ssao.SSAOFilter();
                    ssao.setApproximateNormals(false);
                    post.addFilter(ssao);
                    post.addFilter(new com.jme3.post.filters.CartoonEdgeFilter());
                }
                post.addFilter(new FXAAFilter());
                break;
            }
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        // 第三步：在本帧渲染完成后查询执行顺序，不要主动 execute/close。
        FrameGraph frame = manager.getViewPortFrameGraph(viewPort);
        if (!frame.getExecutionOrder().contains("ShadowAndPostQueue")
            || !frame.getExecutionOrder().contains("PostProcessing")) {
            throw new AssertionError("Missing viewport stages");
        }
        if (post.getFrameGraph() != frame) {
            throw new AssertionError("FPP must append to the viewport graph");
        }
        if (!frame.getExecutedPasses().stream().anyMatch(name -> name.contains("/ShadowMap"))) {
            throw new AssertionError("Missing shadow/Bloom/FXAA passes");
        }
        if (++frames == 1) {
            System.out.println("ENGINE FRAMEGRAPH " + frame.getExecutionOrder());
            // RenderDoc 的调试分组同样使用这些路径，前面再加 ViewPort 名称。
            System.out.println("EXECUTED PASSES\n" + String.join("\n", frame.getExecutedPasses()));
        }
        // 第四步：SPACE 继续沿用原案例的渲染路径切换；上层阶段顺序保持一致。
    }

    public static void main(String[] args) {
        new TestEngineFrameGraph().start();
    }
}
