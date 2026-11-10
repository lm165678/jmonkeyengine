package jme3test.renderpath;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.SceneProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.RenderManager;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Same 1000-light scene at 1600x900; synchronized end-to-end timings, not GPU-only timings. */
public class BenchmarkTiledDeferred extends TestTileBasedDeferredShading {
    private final int[] sizes = {128, 128, 32, 16, 16, 16};
    private final RenderPath[] paths = {RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU,
        RenderPath.TiledDeferredGPU, RenderPath.TiledDeferredGPU, RenderPath.TiledDeferredCPU,
        RenderPath.TiledDeferredGPU};
    private int frame, phase;
    private long previous;
    private final double[] times = new double[80];
    private ByteBuffer pixels;
    private static boolean closeView;
    private static boolean downView;

    @Override public void simpleInitApp() {
        FastMath.rand.setSeed(421);
        super.simpleInitApp();
        if (closeView) {
            cam.setLocation(new Vector3f(0, 2, -12));
            cam.lookAt(new Vector3f(0, .1f, -15), Vector3f.UNIT_Y);
        }
        if (downView) {
            cam.setLocation(new Vector3f(0, .3f, -15));
            cam.lookAtDirection(new Vector3f(0, -1, 0), Vector3f.UNIT_Z);
        }
        setDisplayFps(false);
        setDisplayStatView(false);
        guiNode.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        pixels = BufferUtils.createByteBuffer(1600 * 900 * 4);
        configure();
    }

    private void configure() {
        renderManager.setDeferredTileSize(sizes[phase]);
        renderManager.setRenderPath(paths[phase]);
        for (SceneProcessor processor : viewPort.getProcessors()) {
            if (processor instanceof FilterPostProcessor) {
                for (Filter filter : ((FilterPostProcessor) processor).getFilterList()) {
                    if (filter instanceof BloomFilter) filter.setEnabled(phase != 5);
                }
            }
        }
    }

    @Override public void simpleRender(RenderManager manager) {
        pixels.clear();
        renderer.readFrameBuffer(null, pixels);
        long now = System.nanoTime();
        int within = frame % 120;
        if (within >= 40) times[within - 40] = (now - previous) / 1e6;
        previous = now;
        if (++frame % 120 == 0) {
            Arrays.sort(times);
            System.out.println("TILE_CASE_BENCH view=" + (downView ? "down" : closeView ? "near" : "original")
                    + " requested=" + paths[phase] + " active=" + manager.getRenderPipeline(viewPort).getActiveMode()
                    + " tile=" + sizes[phase] + " bloom=" + (phase != 5)
                    + " medianMs=" + times[40] + " p95Ms=" + times[76]);
            if (++phase == paths.length) stop();
            else configure();
        }
    }

    public static void main(String[] args) {
        closeView = Arrays.asList(args).contains("--near");
        downView = Arrays.asList(args).contains("--down");
        System.setProperty("renderpath.smoke", "true");
        BenchmarkTiledDeferred app = new BenchmarkTiledDeferred();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(1600, 900);
        settings.setVSync(false);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }
}
