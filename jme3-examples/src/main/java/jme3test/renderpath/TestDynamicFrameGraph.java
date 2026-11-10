package jme3test.renderpath;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.FrameGraphPipeline;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.RenderTargetDefinition;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 每帧按需建图的完整使用案例。运行 main()：SPACE 添加/移除临时复制 Pass，
 * F 冻结/更新持久化画面，1 切回 Forward，2 安装动态 FrameGraph。
 * 需要 OpenGL 3.3；图仅负责不透明阶段，透明/GUI 仍由引擎处理。
 *
 * <p>buildFrame() 每帧重新声明资源和 Pass，句柄只在本帧有效；底层临时池
 * 按完整资源定义和不重叠的生命周期复用。History 由图独占持有，跨帧保留内容，
 * 不与临时池混用；尺寸变化或切换管线后重新初始化。冻结模式用于展示真实的
 * 跨帧读取，并不是 TAA 实现；TAA 应使用两个持久化资源做 ping-pong。</p>
 *
 * <p>使用注释见 simpleInitApp()/buildFrame()。所有配置在渲染线程执行。
 * 自动验证：gradlew :jme3-examples:dynamicFrameGraphSmoke；检查最终输出像素、
 * 动态 Pass 增删、历史保留、尺寸变化和 Forward 切换。</p>
 */
public class TestDynamicFrameGraph extends SimpleApplication {
    private final RenderTargetDefinition definition = new RenderTargetDefinition(
        1f, 1, Image.Format.Depth24, Image.Format.RGBA16F);
    private FrameGraphPipeline pipeline;
    private boolean extraCopy;
    private boolean freeze;
    private boolean smoke;
    private int frame;
    private byte[] reference;

    @Override
    public void simpleInitApp() {
        cam.setLocation(new Vector3f(0, 0, 5));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        viewPort.setBackgroundColor(ColorRGBA.Black);
        Geometry cube = new Geometry("Opaque", new Box(1, 1, 1));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.Red);
        cube.setMaterial(material);
        rootNode.attachChild(cube);

        // 第一步：安装一次动态管线，回调由引擎在每帧的不透明阶段调用。
        // 管线先 beginFrame(width, height)，再调用 buildFrame，最后 compile/execute。
        installDynamicPipeline();
        inputManager.addMapping("extra", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("freeze", new KeyTrigger(KeyInput.KEY_F));
        inputManager.addMapping("forward", new KeyTrigger(KeyInput.KEY_1));
        inputManager.addMapping("dynamic", new KeyTrigger(KeyInput.KEY_2));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("extra")) {
                extraCopy = !extraCopy;
            } else if (name.equals("freeze")) {
                freeze = !freeze;
            } else if (name.equals("forward")) {
                renderManager.setPipeline(viewPort, new ForwardPipeline());
            } else {
                installDynamicPipeline();
            }
        }, "extra", "freeze", "forward", "dynamic");
        if (smoke) {
            setDisplayFps(false);
            setDisplayStatView(false);
            flyCam.setEnabled(false);
        }
    }

    private void installDynamicPipeline() {
        pipeline = new FrameGraphPipeline(this::buildFrame);
        renderManager.setPipeline(viewPort, pipeline);
    }

    private void buildFrame(FrameGraph graph) {
        // 第二步：重新获取本帧句柄。History 的分配/内容跨帧保留，句柄不保留。
        ResourceHandle<RenderTarget> history =
            graph.findOrCreatePersistentResource("History", definition);
        ResourceHandle<FrameBuffer> output = graph.importResource(
            "Output", FrameBuffer.class, viewPort::getOutputFrameBuffer);

        // 第三步：只有需要刷新画面或历史已失效时，才创建绘制 Pass 和临时资源。
        // resize 在回调前处理，首次/resize/重新安装后 valid 都为 false。
        if (!freeze || !graph.isPersistentResourceValid("History")) {
            ResourceHandle<RenderTarget> hdr = graph.findOrCreate("HDR", definition);
            graph.addPass("Opaque", context -> {
                renderer.setFrameBuffer(context.get(hdr).getFrameBuffer());
                renderer.setBackgroundColor(viewPort.getBackgroundColor());
                renderer.clearBuffers(true, true, true);
                renderManager.setCamera(cam, false);
                context.getRenderQueue().renderQueue(RenderQueue.Bucket.Opaque, renderManager, cam);
            }).write(hdr);

            // 第四步：按当帧开关增加 Pass。相同定义的 HDR 和 Scratch 仍是不同资源，
            // 因为复制时二者同时存活，池不能让它们使用同一个物理目标。
            ResourceHandle<RenderTarget> source = hdr;
            if (extraCopy) {
                ResourceHandle<RenderTarget> scratch = graph.findOrCreate("Scratch", definition);
                graph.addPass("OptionalCopy", context -> {
                    renderer.clearBuffers(true, true, true);
                    renderer.copyFrameBuffer(context.get(hdr).getFrameBuffer(),
                        context.get(scratch).getFrameBuffer(), true);
                }).read(hdr).write(scratch);
                source = scratch;
            }

            // 第五步：将当帧结果保存到持久化资源。写持久资源是跨帧副作用，不会被裁剪。
            ResourceHandle<RenderTarget> finalSource = source;
            graph.addPass("StoreHistory", context -> {
                renderer.copyFrameBuffer(context.get(finalSource).getFrameBuffer(),
                    context.get(history).getFrameBuffer(), true);
            }).read(finalSource).write(history);
        } else {
            // 冻结时主动消费不透明队列，避免 RenderManager 将未消费的物体再绘制一次。
            graph.addPass("SkipFrozenGeometry", context -> {
                context.getRenderQueue().getOpaqueQueue().clear();
            }).sideEffect();
        }

        // 第六步：真正输出画面。output 为 null 时，copyFrameBuffer 写默认帧缓冲。
        // 这里同时复制深度，让后续透明物体沿用已有的不透明深度。
        graph.addPass("Present", context -> {
            renderer.copyFrameBuffer(context.get(history).getFrameBuffer(), context.get(output), true);
        }).read(history).write(output);
        // 第七步：无需手动 execute；回调结束后由 FrameGraphPipeline 编译并执行。
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (!smoke || ++frame % 20 != 0) {
            return;
        }
        ByteBuffer buffer = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBuffer(viewPort.getOutputFrameBuffer(), buffer);
        byte[] pixels = new byte[buffer.capacity()];
        buffer.clear();
        buffer.get(pixels);
        if (frame == 20) {
            reference = pixels;
            int center = ((cam.getHeight() / 2) * cam.getWidth() + cam.getWidth() / 2) * 4;
            if ((pixels[center] & 255) < 200) {
                throw new AssertionError("Present did not draw the red cube");
            }
            extraCopy = true;
        } else if (frame <= 80 && !Arrays.equals(reference, pixels)) {
            throw new AssertionError("Dynamic/history/Forward output mismatch at " + frame);
        }
        if (frame == 40) {
            if (!pipeline.getGraph().getExecutionOrder().contains("OptionalCopy")) {
                throw new AssertionError("Missing optional pass");
            }
            freeze = true;
        } else if (frame == 60) {
            if (pipeline.getGraph().getExecutionOrder().contains("Opaque")) {
                throw new AssertionError("Frozen frame should reuse history");
            }
            renderManager.setPipeline(viewPort, new ForwardPipeline());
        } else if (frame == 80) {
            installDynamicPipeline();
            cam.resize(320, 240, true);
            renderManager.notifyReshape(320, 240);
        } else if (frame == 100) {
            if (!pipeline.getGraph().isPersistentResourceValid("History")) {
                throw new AssertionError("History was not rebuilt after resize");
            }
            System.out.println("DYNAMIC_FRAMEGRAPH_PASS output, topology, history, resize, Forward");
            stop();
        }
    }

    public static void main(String[] args) {
        TestDynamicFrameGraph app = new TestDynamicFrameGraph();
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setSamples(0);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(app.smoke ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }
}
