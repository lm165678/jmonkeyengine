package jme3test.renderpath;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.deferred.TiledDeferredProcessor;
import com.jme3.renderer.deferred.DeferredPipeline;
import com.jme3.renderer.deferred.LightCullingMode;
import com.jme3.renderer.pipeline.RenderPipeline;
import com.jme3.renderer.pipeline.ForwardPipeline;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;

/** Runs each original scene through all render paths in a real offscreen GL context. */
public final class RenderPathSmoke extends BaseAppState {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU, RenderPath.Forward,
        RenderPath.TiledDeferredCPU, RenderPath.Forward};
    private int frame;
    private RenderPathExample example;
    private byte[] forward, deferred, cpu;
    private static String testView = "original";

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            testView = args[1];
        }
        System.setProperty("renderpath.smoke", "true");
        RenderPathExample app = (RenderPathExample) Class.forName("jme3test.renderpath." + args[0])
                                    .getDeclaredConstructor()
                                    .newInstance();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        settings.setSamples(0);
        settings.setGraphicsDebug(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.getStateManager().attach(new RenderPathSmoke());
        Thread timeout = new Thread(() -> {
            try {
                Thread.sleep(180000);
            } catch (InterruptedException ignored) {
                return;
            }
            System.err.println("Render-path smoke timed out");
            System.exit(2);
        }, "renderpath-test-timeout");
        timeout.setDaemon(true);
        timeout.start();
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    protected void initialize(Application app) {
        example = (RenderPathExample) app;
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
    public void render(RenderManager manager) {
        if (frame == 120 && !"original".equals(testView)) {
            com.jme3.renderer.Camera camera = example.getCamera();
            if ("down".equals(testView)) {
                camera.setLocation(new com.jme3.math.Vector3f(0, .3f, -15));
                camera.lookAtDirection(
                    new com.jme3.math.Vector3f(0, -1, 0), com.jme3.math.Vector3f.UNIT_Z);
            } else if ("near".equals(testView)) {
                camera.setLocation(new com.jme3.math.Vector3f(0, 2, -12));
                camera.lookAt(
                    new com.jme3.math.Vector3f(0, .1f, -15), com.jme3.math.Vector3f.UNIT_Y);
            } else {
                throw new IllegalArgumentException("Unknown test view: " + testView);
            }
        }
        if (frame == 120) {
            manager.setPipeline(example.getViewPort(), null);
        }
        int index = (frame - 120) / 30;
        if (frame >= 120 && index < PATHS.length) {
            manager.setRenderPath(PATHS[index]);
            if (Boolean.getBoolean("renderpath.direct")) {
                RenderPipeline current = manager.getPipeline(example.getViewPort());
                if (PATHS[index] == RenderPath.Forward) {
                    if (!(current instanceof ForwardPipeline)) {
                        manager.setPipeline(example.getViewPort(), new ForwardPipeline());
                    }
                } else {
                    if (!(current instanceof DeferredPipeline)) {
                        current = new DeferredPipeline(
                            example.getAssetManager(), manager.getDeferredShadingModels());
                        manager.setPipeline(example.getViewPort(), current);
                    }
                    DeferredPipeline deferred = (DeferredPipeline) current;
                    deferred.setLightCullingMode(PATHS[index] == RenderPath.Deferred
                            ? LightCullingMode.None
                            : PATHS[index] == RenderPath.TiledDeferredCPU ? LightCullingMode.CPU
                                                                          : LightCullingMode.GPU);
                }
            }
        }
    }

    @Override
    public void postRender() {
        // Probe baking completes on application callbacks. Keep rendering until
        // these callbacks run, otherwise shutdown strands the baking workers.
        if (frame == 119) {
            for (com.jme3.light.Light light : example.getRootNode().getWorldLightList()) {
                if (light instanceof com.jme3.light.LightProbe
                    && !((com.jme3.light.LightProbe) light).isReady()) {
                    return;
                }
            }
            final long time = example.getTimer().getTime();
            final long resolution = example.getTimer().getResolution();
            example.setTimer(new com.jme3.system.Timer() {
                public long getTime() {
                    return time;
                }
                public long getResolution() {
                    return resolution;
                }
                public float getFrameRate() {
                    return 60;
                }
                public float getTimePerFrame() {
                    return 0;
                }
                public void update() {
                }
                public void reset() {
                }
            });
            example.getGuiNode().setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        }
        if (frame >= 120 && (frame - 120) % 30 == 29) {
            int index = (frame - 120) / 30;
            RenderPipeline installed =
                example.getRenderManager().getPipeline(example.getViewPort());
            // Automatic GPU Scene decorates the shading path. Inspect the borrowed
            // downstream pipeline; do not initialize or clean it up independently.
            while (installed instanceof com.jme3.renderer.gpuscene.GpuScenePipeline) {
                installed = ((com.jme3.renderer.gpuscene.GpuScenePipeline) installed).getShadingPipeline();
            }
            DeferredPipeline pipeline = installed instanceof DeferredPipeline
                ? (DeferredPipeline) installed
                : installed instanceof TiledDeferredProcessor
                ? ((TiledDeferredProcessor) installed).getPipeline()
                : null;
            System.out.println("RENDERPATH " + example.getClass().getSimpleName() + " "
                + PATHS[index]
                + " deferred=" + (pipeline == null ? 0 : pipeline.getDeferredGeometryCount())
                + " forward=" + (pipeline == null ? -1 : pipeline.getForwardGeometryCount())
                + " active=" + (pipeline == null ? "Forward" : pipeline.getActivePath())
                + " reason=" + (pipeline == null ? "" : pipeline.getFallbackReason()));
            if (PATHS[index] != RenderPath.Forward
                && (pipeline == null || pipeline.getDeferredGeometryCount() == 0)) {
                throw new AssertionError(
                    "Scene did not exercise deferred rendering: " + PATHS[index]);
            }
            byte[] pixels = capture(PATHS[index].name() + index);
            if (index == 0) {
                forward = pixels;
            }
            if (index == 1) {
                deferred = pixels;
            }
            if (index == 2) {
                cpu = pixels;
                compare(deferred, cpu, 3, "Deferred/CPU");
            }
            if (index == 3) {
                compare(cpu, pixels, 3, "CPU/GPU");
            }
            // Allow one 8-bit rounding step for reordered floating-point probe blends.
            if (index == 4) {
                compare(forward, pixels, 1, "Forward round trip");
            }
            if (index == 5) {
                compare(cpu, pixels, 3, "CPU after recreation");
            }
            if (index == 6) {
                compare(forward, pixels, 1, "Second Forward round trip");
            }
            if (index == PATHS.length - 1) {
                System.out.println("RENDERPATH PASS " + example.getClass().getSimpleName());
                example.stop();
            }
        }
        frame++;
    }

    private byte[] capture(String name) {
        int width = example.getCamera().getWidth(), height = example.getCamera().getHeight();
        java.nio.ByteBuffer buffer = com.jme3.util.BufferUtils.createByteBuffer(width * height * 4);
        example.getRenderer().readFrameBuffer(null, buffer);
        byte[] pixels = new byte[buffer.capacity()];
        buffer.clear();
        buffer.get(pixels);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                image.setRGB(x, height - y - 1,
                    ((pixels[i] & 255) << 16) | ((pixels[i + 1] & 255) << 8)
                        | (pixels[i + 2] & 255));
            }
        }
        try {
            java.io.File directory =
                new java.io.File("build/renderpath-tests/" + example.getClass().getSimpleName());
            directory.mkdirs();
            javax.imageio.ImageIO.write(image, "png", new java.io.File(directory, name + ".png"));
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
        return pixels;
    }

    private void compare(byte[] expected, byte[] actual, int tolerance, String label) {
        int max = 0;
        for (int i = 0; i < expected.length; i++) {
            if (i % 4 != 3) {
                max = Math.max(max, Math.abs((expected[i] & 255) - (actual[i] & 255)));
            }
        }
        System.out.println("RENDERPATH PIXELS " + label + " max=" + max);
        if (max > tolerance) {
            throw new AssertionError(label + " pixel error=" + max);
        }
    }
}
