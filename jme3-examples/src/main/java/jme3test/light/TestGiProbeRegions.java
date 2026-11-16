package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Distinct regional GI with overlapping mixed-density/precision/resolution volumes.
 * Entry main, no controls, OpenGL 4.3 for the complete render-path/GpuScene matrix.
 * Run {@code gradlew :jme3-examples:giProbeRegionsSmoke}. The baker captures red and
 * green uniform backgrounds into separate volumes before adding a single shared
 * receiver. This isolates region selection/blending from geometric light transport.
 * Checks region colors, energy normalization, smooth overlap and uncovered fallback.
 * Also samples across an equal-distance-to-two-faces line: the green contribution
 * must keep increasing across it rather than acquire a nearest-face plateau.
 * APIs: GiSceneBaker, GiProbeVolume, GiProbeSet.Entry and GiProbeSet.Binding.
 */
public final class TestGiProbeRegions extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private GiProbeVolume red;
    private GiProbeVolume green;
    private GiProbeSet set;
    private GiProbeSet.Binding binding;
    private int frame;

    public static void main(String[] args) {
        TestGiProbeRegions app = new TestGiProbeRegions();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(240, 180);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // Low-level GI ownership test; ordinary loaded scenes use automatic services.
        getSceneServices().setEnabled(false);
        // 第一步：区域 A 与 B 独立配置，在 x=[-1,1] 内重叠；B 使用更密的网格。
        red = new GiProbeVolume();
        red.configure(new Vector3f(4, 2, 4), 2, 2, 2);
        red.setLocalTranslation(-3, -1, -2);
        green = new GiProbeVolume();
        green.configure(new Vector3f(4, 2, 4), 5, 3, 3);
        green.setLocalTranslation(-1, -1, -2);
        rootNode.attachChild(red);
        rootNode.attachChild(green);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
        quality.captureSize = 8;
        quality.radianceSamples = 256;
        quality.visibilitySamples = 4;
        quality.visibilityResolution = 4;
        // This synthetic background is the irradiance under test, not a SkyLight.
        quality.separateSky = false;
        quality.background = new ColorRGBA(0.4f, 0, 0, 1);
        // 第二步：分别烘焙区域光照；测试使用均匀背景，便于检查混合能量。
        GiSceneBaker.bake(renderManager, rootNode, red, quality, view -> { }, () -> false, count -> { });
        quality.compactStorage = false;
        quality.visibilityResolution = 8;
        quality.background = new ColorRGBA(0, 0.4f, 0, 1);
        GiSceneBaker.bake(renderManager, rootNode, green, quality, view -> { }, () -> false, count -> { });
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Ambient", ColorRGBA.Black);
        material.setColor("Specular", ColorRGBA.Black);
        Geometry floor = new Geometry("One receiver crossing both regions", new Box(4, 0.1f, 3));
        floor.setMaterial(material);
        floor.setLocalTranslation(0, -0.1f, 0);
        rootNode.attachChild(floor);
        // 第三步：同一材质绑定多个体积，由 shader 按像素选择与混合。
        set = new GiProbeSet(Arrays.asList(new GiProbeSet.Entry(red.getData(), red.getWorldTranslation(), 1),
                new GiProbeSet.Entry(green.getData(), green.getWorldTranslation(), 1)),
                renderer.getLimits().get(Limits.TextureSize), 16L * 1024 * 1024);
        binding = set.bind(material, 0);
        cam.setLocation(new Vector3f(0, 8, 0));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        cam.setParallelProjection(true);
        cam.setFrustum(1, 20, -4, 4, 3, -3);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        viewPort.setRenderPath(PATHS[Math.min(frame / 8, 3)]);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 8 == 7) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(240 * 180 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int[] left = sample(pixels, -2);
            int[] right = sample(pixels, 2);
            int[] center = sample(pixels, 0);
            if (left[0] < 90 || left[1] > 2 || right[1] < 90 || right[0] > 2
                    || center[0] < 5 || center[1] < 5) {
                throw new AssertionError("Incorrect regional GI selection: " + Arrays.toString(left)
                        + " / " + Arrays.toString(center) + " / " + Arrays.toString(right));
            }
            int[] previous = left;
            for (int step = 0; step <= 40; step++) {
                int[] color = sample(pixels, -2 + step * 0.1f);
                if (Math.abs(color[0] + color[1] - 102) > 4
                        || Math.abs(color[0] - previous[0]) > 30 || Math.abs(color[1] - previous[1]) > 30) {
                    throw new AssertionError("Discontinuous or unnormalized overlap: " + Arrays.toString(color));
                }
                previous = color;
            }
            // 第四步：跨越两个边界等距线，验证混合不会在对角线上突然停止变化。
            int[] diagonalBefore = sample(pixels, -0.5f, 1.3f);
            int[] diagonalCenter = sample(pixels, -0.3f, 1.3f);
            int[] diagonalAfter = sample(pixels, -0.1f, 1.3f);
            if (diagonalCenter[1] - diagonalBefore[1] < 2
                    || diagonalAfter[1] - diagonalCenter[1] < 2) {
                throw new AssertionError("Nearest-face ridge in overlap: "
                        + Arrays.toString(diagonalBefore) + " / " + Arrays.toString(diagonalCenter)
                        + " / " + Arrays.toString(diagonalAfter));
            }
            int[] outside = sample(pixels, 3.5f);
            if (outside[0] + outside[1] > 2) {
                throw new AssertionError("GI escaped both coverage bounds");
            }
            System.out.println("GI REGIONS " + viewPort.getRenderPath() + " PASS " + Arrays.toString(center));
            if (frame == 31) {
                stop();
            }
        }
        frame++;
    }

    private int[] sample(ByteBuffer pixels, float x) {
        return sample(pixels, x, 0);
    }

    private int[] sample(ByteBuffer pixels, float x, float z) {
        Vector3f screen = cam.getScreenCoordinates(new Vector3f(x, 0, z));
        int pixel = ((int) screen.y * 240 + (int) screen.x) * 4;
        return new int[]{pixels.get(pixel) & 255, pixels.get(pixel + 1) & 255};
    }

    @Override
    public void destroy() {
        if (binding != null) {
            binding.close();
            set.close();
        }
        if (red != null && red.getData() != null) {
            red.getData().dispose();
        }
        if (green != null && green.getData() != null) {
            green.getData().dispose();
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
