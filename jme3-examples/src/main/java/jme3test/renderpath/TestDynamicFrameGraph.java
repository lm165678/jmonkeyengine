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
 * R 使历史失效，X 移除并重建历史，P 打印依赖/池统计，T 回收空闲临时目标。
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
    private boolean rebuildHistory;
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
        inputManager.addMapping("invalidate", new KeyTrigger(KeyInput.KEY_R));
        inputManager.addMapping("rebuild", new KeyTrigger(KeyInput.KEY_X));
        inputManager.addMapping("inspect", new KeyTrigger(KeyInput.KEY_P));
        inputManager.addMapping("trim", new KeyTrigger(KeyInput.KEY_T));
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
            } else if (name.equals("dynamic")) {
                installDynamicPipeline();
            } else if (renderManager.getPipeline(viewPort) == pipeline) {
                // 在渲染线程的更新阶段操作；不能在绘制 Pass 中释放仍在使用的资源。
                FrameGraph graph = pipeline.getGraph();
                if (name.equals("invalidate")) {
                    // 保留显存，只使内容无效；冻结模式也会在下一次 buildFrame 重建内容。
                    graph.invalidatePersistentResource("History");
                } else if (name.equals("rebuild")) {
                    // 移除需要等 beginFrame 丢弃旧句柄后，在下次回调首次声明前执行。
                    rebuildHistory = true;
                } else if (name.equals("inspect")) {
                    System.out.println(graph.describe());
                } else if (name.equals("trim")) {
                    System.out.println("Released idle targets: " + graph.trimUnusedResources(0));
                }
            }
        }, "extra", "freeze", "forward", "dynamic", "invalidate", "rebuild", "inspect", "trim");
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

    /**
     * 四种资源声明方式的用法对照（以下代码放在每帧建图回调中）：
     * <pre>{@code
     * // 第一步：需要一个新的逻辑临时目标时，用 createResource。
     * // 同一轮声明中名字必须唯一；重复 createResource("Scratch", ...) 会报错。
     * // “create”仅声明句柄，不代表立即创建纹理，更不代表每帧重新分配显存。
     * ResourceHandle<RenderTarget> scratch = graph.createResource("Scratch", definition);
     *
     * // 第二步：多个构建方法需要取得同一个本帧临时目标时，用 findOrCreate。
     * ResourceHandle<RenderTarget> hdr = graph.findOrCreate("HDR", definition);
     * ResourceHandle<RenderTarget> sameHdr = graph.findOrCreate("HDR", definition);
     * // hdr == sameHdr；同名但 definition 不相等或生命周期不同会报错。
     * // 它查找本帧逻辑声明，不会按名字找回上一帧的图像内容。
     * // 与 createResource 一样，使用前必须写入，最后一个读者结束后可回收复用。
     *
     * // 第三步：需要保留上一帧内容时，使用独占的持久资源。
     * ResourceHandle<RenderTarget> history =
     *         graph.findOrCreatePersistentResource("History", definition);
     * // 首次、resize 或 clearResources 后先检查有效性，再安排初始化写入。
     * boolean needsInitialization = !graph.isPersistentResourceValid("History");
     * // 历史内容不会借给临时目标；clearResources/close 才会清理其分配。
     * // TAA 同时读旧历史、写新历史时，用两个持久目标交替，不要纹理读写反馈。
     *
     * // 第四步：外部已经拥有的目标，用 importResource 登记依赖。
     * ResourceHandle<FrameBuffer> output = graph.importResource(
     *         "Output", FrameBuffer.class, viewPort::getOutputFrameBuffer);
     * // 不创建、不接管销毁、不自动呈现。getter 每次执行首次访问时调用一次。
     * // 真正输出见下面的 Present Pass：声明 read(history)/write(output)，并执行复制。
     * }</pre>
     *
     * <p>句柄与显存要分开理解：beginFrame 会使以上所有旧句柄失效，必须重新获取；
     * 持久资源保留的是底层分配和内容。临时池也可以保留底层分配，但不保证内容。
     * 不同名字、相等定义的临时资源，只有生命周期不重叠时才可共用同一物理目标。
     * 本例由 FrameGraphPipeline 调用 beginFrame，不要在回调中重复调用。</p>
     */
    private void buildFrame(FrameGraph graph) {
        // 维护第一步：管线已经 beginFrame，此时旧句柄已失效，可以移除单个历史。
        if (rebuildHistory) {
            graph.removePersistentResource("History");
            rebuildHistory = false;
        }
        // 维护第二步：随后重新声明。同名可用新 definition；本例保持原定义演示重建。
        // 不需要 clearResources，其他历史与临时分配不受影响。
        // 可裁剪动态规划用法：纯资源工作不用默认的强制保留模式。
        // 这里模拟没有消费者的可选效果，Pass 必须被裁剪，也不分配其目标。
        graph.addResourceExpansionPass("OptionalEffect", planned -> {
            ResourceHandle<RenderTarget> unused = planned.createResource("Unused", definition);
            planned.addPass("UnusedDraw", pass -> {
                throw new AssertionError("Unused expanded pass must be culled");
            }).write(unused);
        });
        // 第二步：重新获取本帧句柄。History 的分配/内容跨帧保留，句柄不保留。
        ResourceHandle<RenderTarget> history =
            graph.findOrCreatePersistentResource("History", definition);
        // importResource 借用视口输出，由外部负责尺寸变化和释放。
        // 导入本身不会呈现画面；context.get(output) 为 null 才表示默认帧缓冲。
        ResourceHandle<FrameBuffer> output = graph.importResource(
            "Output", FrameBuffer.class, viewPort::getOutputFrameBuffer);

        // 第三步：只有需要刷新画面或历史已失效时，才创建绘制 Pass 和临时资源。
        // resize 在回调前处理，首次/resize/重新安装后 valid 都为 false。
        if (!freeze || !graph.isPersistentResourceValid("History")) {
            // findOrCreate 是临时资源；重复获取必须同名、同类型且定义相等。
            // HDR 内容不跨帧保留，下面 Opaque 每帧清空并重新写入。
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
                // 这里也可用 createResource("Scratch", definition)：本回调只声明一次。
                // findOrCreate 则允许本帧其他构建方法再次取得同一逻辑 Scratch。
                ResourceHandle<RenderTarget> scratch = graph.findOrCreate("Scratch", definition);
                graph.addPass("OptionalCopy", context -> {
                    // 完整 blit 会覆盖目标，不需要清空。此时仍绑定着 HDR 源，
                    // 在这里直接 clearBuffers 会把刚渲染的源画面清掉。
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
        } else if (frame <= 100 && !Arrays.equals(reference, pixels)) {
            int max = 0;
            int changed = 0;
            for (int i = 0; i < pixels.length; i++) {
                int difference = Math.abs((pixels[i] & 255) - (reference[i] & 255));
                max = Math.max(max, difference);
                if (difference != 0) {
                    changed++;
                }
            }
            throw new AssertionError("Dynamic/history/Forward output mismatch at " + frame
                + ", max=" + max + ", changed channels=" + changed);
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
            // 冻结场景中先验证失效与回收，再于后续帧验证重建画面。
            pipeline.getGraph().invalidatePersistentResource("History");
            if (pipeline.getGraph().isPersistentResourceValid("History")) {
                throw new AssertionError("History invalidation failed");
            }
            pipeline.getGraph().trimUnusedResources(0);
            rebuildHistory = true;
        } else if (frame == 80) {
            if (!pipeline.getGraph().isPersistentResourceValid("History")) {
                throw new AssertionError("History was not rebuilt after removal");
            }
            if (!pipeline.getGraph().getCulledPasses().contains("OptionalEffect/UnusedDraw")) {
                throw new AssertionError("Resource expansion did not cull unused work");
            }
            System.out.println(pipeline.getGraph().describe());
            renderManager.setPipeline(viewPort, new ForwardPipeline());
        } else if (frame == 100) {
            installDynamicPipeline();
            cam.resize(320, 240, true);
            renderManager.notifyReshape(320, 240);
        } else if (frame == 120) {
            if (!pipeline.getGraph().isPersistentResourceValid("History")) {
                throw new AssertionError("History was not rebuilt after resize");
            }
            System.out.println("DYNAMIC_FRAMEGRAPH_PASS output, topology, history, resize, Forward");
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            System.err.println(message);
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
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
