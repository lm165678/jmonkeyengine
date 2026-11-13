package jme3test.renderpath;

import com.jme3.app.SimpleApplication;
import com.jme3.light.DirectionalLight;
import com.jme3.light.PointLight;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Verifies upstream PBR point/spot attenuation, sunlight exposure and material fog
 * across Forward, Deferred and CPU/GPU tiled paths. Requires OpenGL 4.3 and five
 * floating-point MRTs. Entry: main(); no input controls, exits automatically.
 * Verify with {@code gradlew :jme3-examples:run -Pexample=jme3test.renderpath.TestPbrDeferredParity}.
 * GPU Scene is disabled to isolate shading. A maximum of four 8-bit levels is
 * allowed for half-float GBuffer quantization. No source assets are modified.
 * <pre>{@code
 * // 第一步：从官方 j3md 创建 PBR 材质，普通光照与延迟路径共享材质。
 * Material material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
 * material.setFloat("Roughness", 0.5f);
 * // 第二步：在渲染线程切换路径；默认 Forward，可随时恢复。
 * renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
 * renderManager.setRenderPath(RenderPath.Forward);
 * // 第三步：PBR 材质保留 StaticSunIntensity；地形对应新版 StaticSunExposure。
 * material.setFloat("StaticSunIntensity", 0.4f);
 * // 不支持 MRT/compute 时会回退；验证程序明确要求支持这些能力。
 * }</pre>
 */
public final class TestPbrDeferredParity extends SimpleApplication {
    private static final RenderPath[] PATHS = {
        RenderPath.Forward, RenderPath.Deferred, RenderPath.TiledDeferredCPU,
        RenderPath.TiledDeferredGPU
    };
    private Material material;
    private int frame;
    private int phase;
    private byte[] reference;

    public static void main(String[] args) {
        TestPbrDeferredParity app = new TestPbrDeferredParity();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(320, 240);
        settings.setGammaCorrection(false);
        settings.setGpuSceneEnabled(false);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setEnabled(false);
        cam.setLocation(new Vector3f(0, 0, 6));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        viewPort.setBackgroundColor(ColorRGBA.Black);
        material = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
        material.setColor("BaseColor", new ColorRGBA(0.5f, 0.25f, 0.1f, 1));
        material.setFloat("Roughness", 0.5f);
        Geometry geometry = new Geometry("PBR parity", new Sphere(32, 48, 1));
        geometry.setMaterial(material);
        rootNode.attachChild(geometry);
        rootNode.addLight(new PointLight(new Vector3f(2, 2, 3), ColorRGBA.White.mult(20), 20));
        SpotLight spot = new SpotLight();
        spot.setPosition(new Vector3f(-2, 1, 3));
        spot.setDirection(spot.getPosition().negate().normalizeLocal());
        spot.setSpotRange(20);
        spot.setColor(new ColorRGBA(4, 8, 12, 1));
        rootNode.addLight(spot);
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-1, -1, -1).normalizeLocal());
        rootNode.addLight(sun);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (++frame % 15 != 0) {
            return;
        }
        int pathIndex = phase % PATHS.length;
        if (pathIndex != 0 && (manager.getRenderPipeline(viewPort) == null
                || manager.getRenderPipeline(viewPort).getDeferredGeometryCount() != 1)) {
            throw new AssertionError("PBR did not enter deferred rendering");
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(320 * 240 * 4);
        renderer.readFrameBufferWithFormat(null, pixels, Image.Format.RGBA8);
        byte[] current = new byte[pixels.capacity()];
        pixels.clear();
        pixels.get(current);
        if (pathIndex == 0) {
            reference = current;
        } else {
            int maximum = 0;
            for (int index = 0; index < current.length; index++) {
                if (index % 4 != 3) {
                    maximum = Math.max(maximum,
                            Math.abs((current[index] & 255) - (reference[index] & 255)));
                }
            }
            System.out.println("PBR_PARITY path=" + PATHS[pathIndex]
                    + " fogExposure=" + (phase >= PATHS.length) + " max=" + maximum);
            if (maximum > 4) {
                throw new AssertionError("Forward/deferred PBR mismatch: " + maximum);
            }
        }
        phase++;
        if (phase == PATHS.length * 2) {
            System.out.println("PBR_DEFERRED_PARITY_PASS");
            stop();
            return;
        }
        if (phase == PATHS.length) {
            material.setFloat("StaticSunIntensity", 0.4f);
            material.setBoolean("UseFog", true);
            material.setColor("FogColor", new ColorRGBA(0.2f, 0.3f, 0.4f, 1));
            material.setFloat("ExpFog", 0.02f);
        }
        manager.setRenderPath(PATHS[phase % PATHS.length]);
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
