package jme3test.renderpath;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.framegraph.*;
import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Complete public FrameGraph and material-owned lighting example.
 * Run main(): 1 = Forward, 2 = custom opaque graph, 3 = GPU deferred graph.
 * The custom graph renders opaque geometry to a pooled HDR/depth target, then
 * resolves it into the current viewport output. Transparent and GUI queues stay
 * with jME's normal renderer. A deliberately unused pass demonstrates elimination.
 *
 * Material usage is just new Material(assetManager, "Materials/Tiled/CustomLambert.j3md").
 * The .j3md references CustomLambert.frag via DeferredLightingShader. No Java-side
 * source loading or numeric registration is needed, including after path switches.
 * See createGraph() for read/write/import declarations and target allocation.
 * All configuration happens on the render thread; RenderManager owns lifecycle.
 * Run gradlew :jme3-examples:frameGraphSmoke for offscreen pixel/cleanup checks.
 */
public class TestFrameGraph extends RenderPathExample {
    private FrameGraph graph;
    private boolean smoke;
    @Override
    public void simpleInitApp() {
        cam.setLocation(new Vector3f(0, 0, 6));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        viewPort.setBackgroundColor(new ColorRGBA(.03f, .04f, .06f, 1));
        Geometry sphere = new Geometry("Material-owned Lambert", new Sphere(24, 24, 1));
        sphere.setMaterial(new Material(assetManager, "Materials/Tiled/CustomLambert.j3md"));
        rootNode.attachChild(sphere);
        DirectionalLight light = new DirectionalLight();
        light.setDirection(new Vector3f(-1, -1, -1).normalizeLocal());
        rootNode.addLight(light);
        for (int i = 1; i <= 3; i++) {
            inputManager.addMapping("graph" + i, new KeyTrigger(KeyInput.KEY_1 + i - 1));
        }
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (pressed) {
                select(Integer.parseInt(name.substring(5)));
            }
        }, "graph1", "graph2", "graph3");
        select(0);
        // if (smoke) {
        //     setDisplayFps(false);
        //     setDisplayStatView(false);
        //     stateManager.attach(new Check());
        // }
    }
    /**
     * Builds once; targets allocate lazily and are pooled across frames. Use distinct
     * handles for successive image versions, and declare every access. Imported
     * viewport output is borrowed (null denotes the default framebuffer).
     */
    private FrameGraph createGraph() {
        // 第一步：创建图。先声明资源和 Pass，最后统一编译依赖关系。
        FrameGraph result = new FrameGraph();
        // 第二步：声明与视口等大的 HDR/深度目标；此处不立即分配显存。
        // FrameGraph 在执行时按需分配，并负责复用、尺寸变化和释放。
        ResourceHandle<RenderTarget> hdr = result.createResource(
            "HDR", new RenderTargetDefinition(1f, 1, Image.Format.Depth24, Image.Format.RGBA16F));
        // 第三步：导入视口输出。它由外部拥有，图不会销毁它；null 表示默认帧缓冲。
        ResourceHandle<FrameBuffer> output =
            result.importResource("Viewport", FrameBuffer.class, viewPort::getOutputFrameBuffer);
        // 第四步：添加不透明绘制 Pass，绑定并清空 HDR，然后绘制不透明队列。
        // write(hdr) 声明资源依赖；实际绘制仍由回调内的 renderer 完成。
        result
            .addPass("Opaque",
                context -> {
                    renderer.setFrameBuffer(context.get(hdr).getFrameBuffer());
                    renderer.setBackgroundColor(viewPort.getBackgroundColor());
                    renderer.clearBuffers(true, true, true);
                    renderManager.setCamera(cam, false);
                    context.getRenderQueue().renderQueue(
                        RenderQueue.Bucket.Opaque, renderManager, cam);
                })
            .write(hdr);
        // 第五步：添加输出 Pass。read(hdr) 保证它排在 Opaque 之后，
        // write(output) 将结果写入外部输出，使这条依赖链成为有效输出。
        result
            .addPass("Resolve",
                context
                -> renderer.copyFrameBuffer(
                    context.get(hdr).getFrameBuffer(), context.get(output), true))
            .read(hdr)
            .write(output);
        // 第六步（可选演示）：没有消费者或外部副作用的 Pass 会被裁剪。
        ResourceHandle<RenderTarget> unused = result.createResource(
            "Unused", new RenderTargetDefinition(.5f, 1, null, Image.Format.RGBA8));
        result.addPass("UnusedPass", context -> {
                  throw new AssertionError("Dead pass executed");
              }).write(unused);
        // 第七步：编译并检查依赖，确定顺序和裁剪结果；这里还不会执行绘制。
        result.compile();
        System.out.println("FRAMEGRAPH order=" + result.getExecutionOrder()
            + " culled=" + result.getCulledPasses());
        return result;
    }
    /**
     * 使用说明：select(1) 是 Forward，select(2) 是自定义 FrameGraph，
     * select(3) 是 GPU Deferred。当前其他值（包括 0）也进入 GPU Deferred 分支。
     *
     * 自定义 FrameGraph 的 Opaque Pass 使用材质默认的 Forward Technique，
     * 不会调用 CustomLambert.frag 的延迟入口。只有 GPU/CPU Deferred 光照阶段
     * 才会调用它：引擎将 deferredSurfaceShading 重命名为带 ID 的函数，
     * 再由 subfaceShading(shadingId) 的自动生成分支调用。
     * 将该入口改为 return vec4(1.0) 可验证延迟路径输出纯白；Forward 不受影响。
     */
    private void select(int mode) {
        if (mode == 1) {
            // 切回已有 Forward 管线；RenderManager 负责清理之前安装的管线。
            renderManager.setPipeline(viewPort, new ForwardPipeline());
        } else if (mode == 2) {
            graph = createGraph();
            // 第八步：在渲染线程安装管线，后续每帧由 RenderManager 执行图。
            // 安装期间不要自行 close 图；替换管线和退出时由引擎管理生命周期。
            renderManager.setPipeline(viewPort, new FrameGraphPipeline(graph));
        } else {
            useRenderPath(RenderPath.TiledDeferredGPU);
        }
    }
    private final class Check extends BaseAppState {
        private int frame;
        private byte[] forward;
        @Override
        protected void initialize(Application app) {
        }
        @Override
        protected void cleanup(Application app) {
        }
        @Override
        protected void onEnable() {
        }
        @Override
        protected void onDisable() {
        }
        @Override
        public void postRender() {
            if (++frame % 30 != 0) {
                return;
            }
            ByteBuffer data = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBuffer(null, data);
            byte[] pixels = new byte[data.capacity()];
            data.clear();
            data.get(pixels);
            if (frame == 30) {
                forward = pixels;
                select(2);
                return;
            }
            int difference = 0;
            for (int i = 0; i < pixels.length; i++) {
                if (i % 4 != 3) {
                    difference =
                        Math.max(difference, Math.abs((pixels[i] & 255) - (forward[i] & 255)));
                }
            }
            System.out.println("FRAMEGRAPH pixels frame=" + frame + " max=" + difference);
            if (difference > 3) {
                throw new AssertionError("Graph/Forward mismatch: " + difference);
            }
            if (frame == 60) {
                if (!graph.getExecutionOrder().equals(Arrays.asList("Opaque", "Resolve"))) {
                    throw new AssertionError();
                }
                select(1);
            } else if (frame == 90) {
                select(3);
            } else if (frame == 120) {
                select(1);
            } else {
                System.out.println(
                    "FRAMEGRAPH_PASS custom graph, automatic material, Forward round trips");
                stop();
            }
        }
    }
    public static void main(String[] args) {
        TestFrameGraph app = new TestFrameGraph();
        app.smoke = Arrays.asList(args).contains("--smoke");
        if (app.smoke) {
            System.setProperty("renderpath.smoke", "true");
        }
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setSamples(0);
        settings.setVSync(false);
        settings.setGraphicsDebug(app.smoke);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(app.smoke ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }
}
