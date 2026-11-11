package jme3test.renderpath;

import com.jme3.asset.AssetManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.Filter;
import com.jme3.post.FilterGraphContext;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.FrameGraphFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * FG 原生 Filter 的完整案例。SPACE 切换 Forward/Deferred，G 开关原生效果，
 * H 切换半分辨率中间目标。链为原生 A -> 旧 Copy -> 旧 FXAA -> 原生 B。
 * A/B 故意使用相同名字，展示资源/Pass 按实例位置隔离。
 *
 * <p>原生效果包含两次材质绘制：颜色乘 0.5，再乘 2。全分辨率时基本保持原图，
 * 半分辨率时展示采样分辨率变化。只加载 j3md，不在 Java 中拼接或加载 frag。
 * DepthValidation 演示声明并读取原始场景深度；颜色目标本身不带深度。</p>
 *
 * <p>运行 main() 交互查看；gradlew :jme3-examples:frameGraphFilterSmoke 验证混用、
 * 开关、奇数尺寸和半分辨率资源；frameGraphFilterSmokeMSAA 验证 4x MSAA 输入。
 * 需要 OpenGL 3.3/浮点渲染目标；本例请求 4.3，以便同时测试 GPU Deferred。</p>
 */
public class TestFrameGraphFilters extends RenderPathExample {
    private final TwoPassFilter first = new TwoPassFilter();
    private final TwoPassFilter last = new TwoPassFilter();
    private boolean smoke;
    private int frame;
    private byte[] baseline;

    /**
     * 第一步：继承 FrameGraphFilter，保留 initFilter 的材质初始化习惯。
     * 与旧 Filter 不同，不必实现 getMaterial，也不创建 Filter.Pass/FrameBuffer。
     */
    public static class TwoPassFilter extends FrameGraphFilter {
        private Material darken;
        private Material restore;
        private boolean halfResolution;
        private int sceneWidth;
        private int sceneHeight;

        public TwoPassFilter() {
            super("TwoPassColor");
        }

        @Override
        protected void initFilter(AssetManager assets, RenderManager manager, ViewPort viewport, int w, int h) {
            darken = new Material(assets, "Common/MatDefs/Post/FrameGraphCopy.j3md");
            darken.setColor("Color", new ColorRGBA(.5f, .5f, .5f, 1));
            restore = new Material(assets, "Common/MatDefs/Post/FrameGraphCopy.j3md");
            restore.setColor("Color", new ColorRGBA(2, 2, 2, 1));
            sceneWidth = w;
            sceneHeight = h;
        }

        @Override
        protected boolean isRequiresDepthTexture() {
            // 第二步（需要深度时）：明确请求原始场景深度。
            return true;
        }

        @Override
        protected ResourceHandle<RenderTarget> build(FilterGraphContext context,
            ResourceHandle<RenderTarget> input) {
            // 第三步：每帧声明临时资源。比例以 FPP 场景尺寸为基准，不受前一个 Pass 影响。
            // createColor 内部调用 graph.createResource，返回新的逻辑临时目标。
            // 同一个效果本帧不能重复使用相同局部名字；不同效果由 context 自动加前缀隔离。
            // 声明不会立即分配显存，物理目标可从池中复用，但不得依赖上一帧内容。
            // 它不是持久历史图；四类资源的详细用法见 TestDynamicFrameGraph.buildFrame。
            float scale = halfResolution ? .5f : 1f;
            ResourceHandle<RenderTarget> intermediate = context.createColor("Intermediate", scale, Image.Format.RGBA16F);
            ResourceHandle<RenderTarget> output = context.createColor("Output", 1f, Image.Format.RGBA16F);

            // 第四步：声明绘制。助手绑定 Texture/NumSamples 并声明 read/write。
            // 中间结果的最后一个读者完成后，主图可以复用它的物理分配。
            context.draw("Darken", darken, input, intermediate);
            context.draw("Restore", restore, intermediate, output);

            // 第五步：复杂效果也可直接添加 compute/自定义绘制 Pass。
            // 使用 context.name 隔离同名效果，并为每个访问的句柄声明 read/write。
            context.getGraph().addPass(context.name("ValidateResources"), pass -> {
                if (pass.get(context.getDepth()) == null) {
                    throw new AssertionError("Missing original scene depth");
                }
                RenderTarget small = pass.get(intermediate);
                RenderTarget full = pass.get(output);
                if (small.getFrameBuffer().getWidth() != Math.max(1, (int) Math.ceil(sceneWidth * scale))
                    || full.getFrameBuffer().getWidth() != sceneWidth
                    || full.getFrameBuffer().getHeight() != sceneHeight) {
                    throw new AssertionError("Incorrect graph target dimensions");
                }
            }).read(context.getDepth()).read(intermediate).read(output).sideEffect();

            // 第六步：返回颜色输出。不要缓存句柄，不要执行/清理主图。
            // 若本帧无需效果，可直接 return input 实现旁路。
            return output;
        }
    }

    /**
     * 旧 Filter 适配示例：仍然通过 initFilter/getMaterial 提供单次绘制。
     * Copy 材质支持 MSAA；FXAA 本身只支持 sampler2D，必须放在 resolve 之后。
     */
    private static class LegacyCopyFilter extends Filter {
        LegacyCopyFilter() {
            super("LegacyCopy");
        }

        @Override
        protected void initFilter(AssetManager assets, RenderManager manager, ViewPort viewport, int w, int h) {
            material = new Material(assets, "Common/MatDefs/Post/FrameGraphCopy.j3md");
        }

        @Override
        protected Material getMaterial() {
            return material;
        }
    }

    @Override
    public void simpleInitApp() {
        cam.setLocation(new Vector3f(0, 0, 5));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        Geometry cube = new Geometry("Cube", new Box(1, 1, 1));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(.8f, .3f, .1f, 1));
        cube.setMaterial(material);
        rootNode.attachChild(cube);
        useRenderPath(RenderPath.Forward);

        // 第七步：直接加入同一个 FPP。新/旧/旧/新之间的输入和最终输出由 FPP 衔接。
        FilterPostProcessor post = new FilterPostProcessor(assetManager);
        post.setNumSamples(Math.max(1, context.getSettings().getSamples()));
        first.setEnabled(!smoke);
        last.setEnabled(!smoke);
        post.addFilter(first);
        post.addFilter(new LegacyCopyFilter());
        post.addFilter(new FXAAFilter());
        post.addFilter(last);
        viewPort.addProcessor(post);
        inputManager.addMapping("GraphEffects", new KeyTrigger(KeyInput.KEY_G));
        inputManager.addMapping("GraphHalf", new KeyTrigger(KeyInput.KEY_H));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("GraphEffects")) {
                first.setEnabled(!first.isEnabled());
                last.setEnabled(first.isEnabled());
            } else {
                first.halfResolution = !first.halfResolution;
            }
        }, "GraphEffects", "GraphHalf");
        if (smoke) {
            setDisplayFps(false);
            setDisplayStatView(false);
            flyCam.setEnabled(false);
        } else {
            new RenderPathHelper(this);
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!smoke || ++frame % 20 != 0) {
            return;
        }
        ByteBuffer data = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBuffer(null, data);
        byte[] pixels = new byte[data.capacity()];
        data.clear();
        data.get(pixels);
        int center = ((cam.getHeight() / 2) * cam.getWidth() + cam.getWidth() / 2) * 4;
        if ((pixels[center] & 255) < 20) {
            throw new AssertionError("Filter output is unexpectedly black");
        }
        if (frame == 20) {
            baseline = pixels;
            first.setEnabled(true);
            last.setEnabled(true);
        } else if (frame <= 100) {
            int max = 0;
            for (int i = 0; i < pixels.length; i++) {
                max = Math.max(max, Math.abs((pixels[i] & 255) - (baseline[i] & 255)));
            }
            if (max > 2) {
                throw new AssertionError("Graph/legacy mismatch at " + frame + ": " + max);
            }
            if (frame == 40) {
                last.setEnabled(false);
            } else if (frame == 60) {
                first.setEnabled(false);
                last.setEnabled(true);
            } else if (frame == 80) {
                last.setEnabled(false);
            } else {
                first.setEnabled(true);
                last.setEnabled(true);
                first.halfResolution = true;
                cam.resize(321, 239, true);
                renderManager.notifyReshape(321, 239);
                useRenderPath(RenderPath.TiledDeferredGPU);
            }
        } else if (frame == 120) {
            if (!manager.getViewPortFrameGraph(viewPort).getExecutedPasses().stream()
                    .anyMatch(name -> name.contains("TwoPassColor/Restore"))) {
                throw new AssertionError("Native filter passes missing from viewport graph");
            }
            System.out.println("GRAPH_FILTER_PASS mixed chain, toggles, depth, resize, scaled targets");
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
        TestFrameGraphFilters app = new TestFrameGraphFilters();
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(640, 480);
        settings.setSamples(Arrays.asList(args).contains("--msaa") ? 4 : 0);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(app.smoke ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }
}
