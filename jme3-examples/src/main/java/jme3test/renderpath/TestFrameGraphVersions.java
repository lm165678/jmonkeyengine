package jme3test.renderpath;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.FrameGraphPipeline;
import com.jme3.renderer.framegraph.ResourceAccess;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.shader.BufferObject;
import com.jme3.shader.VarType;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 资源版本、外部别名和 GPU 屏障的完整运行案例。需要 OpenGL 4.3（LWJGL2/3 后端）。
 * 同时验证默认帧缓冲的蓝色旧版本读者先于绿色新版本写者执行。
 * main() 显示绿色输出，控制台显示图结构；--smoke 验证 GPU 最终值为 42 后退出。
 * 自动验证：gradlew :jme3-examples:frameGraphVersionsSmoke。
 *
 * <p>compute 源文件由 VersionCompute.j3md 声明，不手动加载 comp 字符串。
 * compute 使用普通 Material 参数和 dispatchCompute；缓冲读回由 Renderer.readBufferData 封装。
 * 上层没有 Shader 获取、裸 GL 绑定或 LWJGL 依赖。后台自动恢复读回绑定状态。
 * FG 的 memory barrier 保证 GPU 访问可见性；阻塞式 readBufferData 才完成 CPU 读回。
 * 不要把这种每帧阻塞读回用于正式的高性能渲染。</p>
 */
public class TestFrameGraphVersions extends SimpleApplication {
    private final Object bufferIdentity = new Object();
    private final Object outputIdentity = new Object();
    private BufferObject buffer;
    private Material compute;
    private FrameGraphPipeline pipeline;
    private boolean smoke;
    private int frame;

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setEnabled(false);
        // 第一步：外部拥有 GPU 缓冲；importResource 借用它，不接管释放。
        buffer = new BufferObject(0, BufferObject.BufferType.ShaderStorageBufferObject);
        buffer.declareField("values", VarType.IntArray);
        buffer.setFieldValue("values", new int[4]);

        // 第二步：通过 j3md 声明 ComputeShader，只允许独立 compute technique。
        compute = new Material(assetManager, "Materials/FrameGraph/VersionCompute.j3md");
        compute.setShaderStorageBufferObject("Values", buffer);

        // 第三步：引擎每帧 beginFrame 后调用 build；句柄不能跨帧缓存。
        pipeline = new FrameGraphPipeline(this::build);
        renderManager.setPipeline(viewPort, pipeline);
    }

    /**
     * // A：写入蓝色。
     * graph.addPass("A", ...).write(color);
     *
     * // B：继续覆盖同一目标，改成绿色。
     * ResourceHandle<RenderTarget> next =
     *         graph.nextVersion("NextColor", color);
     *
     * graph.addPass("B", ...).write(next);
     *
     * // C：虽然最后添加，但要求读取“修改前”的蓝色。
     * graph.addPass("C", ...).read(color).sideEffect();
     *
     * A 写蓝色 → C 读蓝色 → B 改成绿色
     * 另外,同一个资源可以被同一个identity引用为多个句柄,参考下面的initial和alias,指同一个资源句柄
     * @param graph
     */
    private void build(FrameGraph graph) {
        // 第四步：不同模块用同一个 token 导入相同存储，拿到同一逻辑句柄。
        ResourceHandle<BufferObject> initial = graph.importResource(
                "ComputeBuffer", BufferObject.class, bufferIdentity, () -> buffer);
        ResourceHandle<BufferObject> alias = graph.importResource(
                "OtherModuleBuffer", BufferObject.class, bufferIdentity, () -> buffer);
        if (initial != alias) {
            throw new AssertionError("Aliases did not resolve to the same handle");
        }
        graph.addPass("Initialize41", pass -> {
            compute.setShaderStorageBufferObject("Values", pass.get(initial));
            compute.setBoolean("Increment", false);
            compute.dispatchCompute(renderManager, 1, 1, 1);
        }).write(initial, ResourceAccess.STORAGE_BUFFER);

        // 第五步：把 SSBO 想成一块白板。initial 是“写着 41 时”的逻辑名字，
        // incremented 是“修改为 42 后”的逻辑名字，两者始终指向同一块白板。
        // nextVersion 只声明“接下来要改这块白板”，它不会 dispatch、复制或修改数据。
        // 真正加 1 的是下面 IncrementTo42 Pass。FG 保证所有需要 41 的旧读者先执行，
        // 再执行加 1，最后供新读者读取 42。想同时保留 41 和 42 就必须用两个资源。
        // 下一版本仍使用同一个 SSBO；FG 不复制数据，也不额外分配。
        // 同一版本禁止多个 writer，后续修改必须显式声明版本链。
        ResourceHandle<BufferObject> incremented = graph.nextVersion("Incremented", alias);
        graph.addPass("IncrementTo42", pass -> {
            compute.setShaderStorageBufferObject("Values", pass.get(incremented));
            compute.setBoolean("Increment", true);
            compute.dispatchCompute(renderManager, 1, 1, 1);
        }).read(initial, ResourceAccess.STORAGE_BUFFER)
                .write(incremented, ResourceAccess.STORAGE_BUFFER);

        // 第六步：上一 Pass 是 GPU shader 通过 buffer 块访问，所以用 STORAGE_BUFFER。
        // 本 Pass 是通过 Renderer 下载到 Java ByteBuffer，所以用 BUFFER_TRANSFER。
        // 缓冲还是同一个 SSBO，变化的是“谁用什么方式访问它”，而不是资源本身的类型。
        // FG 自动在读回前插入对应屏障，无需应用手写 glMemoryBarrier。
        graph.addPass("Validate42", pass -> {
            ByteBuffer values = BufferUtils.createByteBuffer(16);
            renderer.readBufferData(pass.get(incremented), 0, values);
            if (values.getInt(0) != 42) {
                throw new AssertionError("Expected GPU result 42, got " + values.getInt(0));
            }
        }).read(incremented, ResourceAccess.BUFFER_TRANSFER).sideEffect();

        // 第七步：导入默认输出，实际清屏才显示绿色；import 本身不会绘制。
        ResourceHandle<FrameBuffer> output = graph.importResource(
                "Output", FrameBuffer.class, outputIdentity, viewPort::getOutputFrameBuffer);
        graph.addPass("ClearOldBlue", pass -> {
            renderer.setFrameBuffer(pass.get(output));
            renderer.setBackgroundColor(ColorRGBA.Blue);
            renderer.clearBuffers(true, true, true);
        }).write(output, ResourceAccess.FRAMEBUFFER);
        ResourceHandle<FrameBuffer> presented = graph.nextVersion("Presented", output);
        graph.addPass("PresentSuccess", pass -> {
            renderer.setFrameBuffer(pass.get(presented));
            renderer.setBackgroundColor(ColorRGBA.Green);
            renderer.clearBuffers(true, true, true);
        }).write(presented, ResourceAccess.FRAMEBUFFER);

        // 旧版本读者故意在 Java 代码中写在新版本 writer 后面。
        // FG 根据版本依赖自动将本 Pass 排到 PresentSuccess 之前，读取必须仍为蓝色。
        // 这就是版本的用途：read(output) 明确要旧的蓝色，而 read(presented) 要新的绿色。
        // 单靠 addPass 的先后顺序，无法表达“后声明的这个读者仍要覆盖前的内容”。
        // 当前编译器按 read/write、版本链、after 约束排序；无依赖的就绪 Pass 才按添加顺序。
        // 也可以设计成每次 write 自动生成最新版本，但本 API 选择显式 nextVersion，
        // 使不同模块获取的输入版本明确；不是 GPU 要求调用这个 Java 方法。
        graph.addPass("ReadOldBlue", pass -> {
            ByteBuffer pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBuffer(pass.get(output), pixels);
            if ((pixels.get(0) & 255) != 0 || (pixels.get(2) & 255) != 255) {
                throw new AssertionError("Old framebuffer version was overwritten before its reader");
            }
        }).read(output, ResourceAccess.FRAMEBUFFER).sideEffect();
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (++frame == 10) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBuffer(null, pixels);
            if ((pixels.get(1) & 255) != 255 || (pixels.get(2) & 255) != 0) {
                throw new AssertionError("Final framebuffer version is not green");
            }
            System.out.println(pipeline.getGraph().describe());
            System.out.println("FRAMEGRAPH_VERSIONS_PASS aliases, in-place versions, GPU result 42");
            if (smoke) {
                stop();
            }
        }
    }

    @Override
    public void destroy() {
        // 第八步：导入资源由所有者释放；在渲染结束后调用 dispose 交给原生对象管理器。
        if (buffer != null) {
            buffer.dispose();
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }

    public static void main(String[] args) {
        TestFrameGraphVersions app = new TestFrameGraphVersions();
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(app.smoke ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }
}
