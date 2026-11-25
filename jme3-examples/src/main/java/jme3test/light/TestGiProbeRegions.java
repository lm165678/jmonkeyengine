package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
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
 * Checks exclusive local illumination, normalized automatic and explicit fades,
 * fallback past invalid volumes, and zero contribution outside both regions.
 * Also samples across an equal-distance-to-two-faces line: the green contribution
 * must keep increasing across it rather than acquire a nearest-face plateau.
 * APIs: GiSceneBaker, GiProbeVolume, GiProbeSet.Entry and GiProbeSet.Binding.
 * The test repeats each check with lighting-pass GI (default) and material-pass
 * GI, switching through RenderManager.setDeferredProbeGiInLighting on the render
 * thread. The lighting path requires seven floating-point-capable MRT slots and
 * at least twenty fragment texture units. Unsupported draws use material GI.
 */
public final class TestGiProbeRegions extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private GiProbeVolume red;
    private GiProbeVolume green;
    private GiProbeData invalid;
    private Material material;
    private GiProbeSet set;
    private GiProbeSet.Binding binding;
    private int frame;
    private boolean lightingPayload;
    private final boolean halfRegression = Boolean.getBoolean("gi.test.halfResolution");
    private byte[] reference;
    private int halfBatches;
    private int maximumDifference;
    private int approximateDifference;

    public static void main(String[] args) {
        TestGiProbeRegions app = new TestGiProbeRegions();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(app.halfRegression ? 241 : 240, app.halfRegression ? 181 : 180);
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
        viewPort.setProbeGiObserver((batch, half, resolved) -> halfBatches++);
        viewPort.setGBufferObserver((batch, target) -> lightingPayload |= target.getColorCount() == 7);
        // 第一步：使用大 Volume 覆盖场景，再在内部放置较小的 Volume。
        // 两者均保留默认 FadeDistance=0，边缘采用局部 Volume 的一格过渡宽度。
        red = new GiProbeVolume();
        red.configure(new Vector3f(7, 2, 5), 2, 2, 2);
        red.setLocalTranslation(-3.5f, -0.01f, -2.5f);
        green = new GiProbeVolume();
        green.configure(new Vector3f(3, 2, 3.2f), 5, 3, 5);
        green.setLocalTranslation(-1.5f, -0.01f, -1.6f);
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
        // 创建更小但没有有效探针的网格，验证能继续查找可用区域。
        GiProbeVolume rejected = new GiProbeVolume();
        rejected.configure(new Vector3f(1, 1, 1), 2, 2, 2);
        quality.classifyProbes = true;
        quality.validator = (scene, position, spacing, clearance, store) -> false;
        invalid = GiSceneBaker.bake(renderManager, rootNode, rejected, quality,
                view -> { }, () -> false, count -> { });
        material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Ambient", ColorRGBA.Black);
        material.setColor("Specular", ColorRGBA.Black);
        Geometry floor = new Geometry("One receiver crossing both regions", new Box(4, 0.1f, 3));
        floor.setMobility(Mobility.Dynamic);
        floor.setMaterial(material);
        floor.setLocalTranslation(0, -0.1f, 0);
        rootNode.attachChild(floor);
        // 第三步：同一材质绑定多个体积，由 shader 按像素选择与混合。
        installAtlas(-0.01f, 0);
        cam.setLocation(new Vector3f(0, 8, 0));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        cam.setParallelProjection(true);
        cam.setFrustum(1, 20, -4, 4, 3, -3);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (frame > 0 && frame % 32 == 0) {
            // 第五步：局部设置正淡出距离，外层应补足覆盖，过渡带保持总能量。
            binding.close();
            set.close();
            boolean explicit = frame % 64 == 32;
            installAtlas(explicit ? -1f : -0.01f, explicit ? 0.75f : 0f);
        }
        // 在渲染线程切换回退选项，对照同一材质和同一份烘焙。
        renderManager.setDeferredProbeGiInLighting(frame < 64);
        lightingPayload = false;
        halfBatches = 0;
        viewPort.setHalfResolutionProbeGi(halfRegression && frame % 8 >= 4 && frame % 8 <= 5);
        viewPort.setProbeGiFullFallback(frame % 8 != 5);
        viewPort.setRenderPath(PATHS[(frame / 8) % PATHS.length]);
    }

    private void installAtlas(float y, float localFade) {
        set = new GiProbeSet(Arrays.asList(
                new GiProbeSet.Entry(red.getData(), new Vector3f(-3.5f, y, -2.5f), 0),
                new GiProbeSet.Entry(green.getData(), new Vector3f(-1.5f, y, -1.6f), localFade),
                new GiProbeSet.Entry(invalid, new Vector3f(-0.5f, -0.5f, -0.5f), 0),
                new GiProbeSet.Entry(invalid, new Vector3f(-0.45f, -0.5f, -0.5f), 0)),
                renderer.getLimits().get(Limits.TextureSize), 16L * 1024 * 1024);
        binding = set.bind(material, 0);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (halfRegression && frame % 8 >= 3) {
            ByteBuffer image = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), image, Image.Format.RGBA8);
            if (frame % 8 == 3) {
                reference = new byte[image.capacity()];
                image.get(reference);
            } else {
                int difference = 0;
                for (int i = 0; i < reference.length; i++) {
                    difference = Math.max(difference, Math.abs((image.get(i) & 255) - (reference[i] & 255)));
                }
                if (viewPort.isProbeGiFullFallback() || !viewPort.isHalfResolutionProbeGi()) {
                    maximumDifference = Math.max(maximumDifference, difference);
                } else {
                    approximateDifference = Math.max(approximateDifference, difference);
                }
                int allowed = viewPort.isHalfResolutionProbeGi() ? 4 : 0;
                if (difference > allowed) {
                    throw new AssertionError("GI resolution switch frame=" + frame + " max=" + difference);
                }
                boolean expected = viewPort.isHalfResolutionProbeGi() && lightingPayload
                        && renderer.getLimits().get(Limits.FragmentTextureUnits) >= 24;
                if ((halfBatches > 0) != expected) {
                    throw new AssertionError("Half GI pass selection frame=" + frame);
                }
            }
            BufferUtils.destroyDirectBuffer(image);
        }
        if (frame % 8 == 7) {
            boolean supported = renderer.getLimits().get(Limits.FrameBufferMrtAttachments) >= 7
                    && renderer.getLimits().get(Limits.FragmentTextureUnits) >= 20;
            boolean expectedPayload = supported && frame < 64 && viewPort.getRenderPath() != RenderPath.Forward;
            if (lightingPayload != expectedPayload) {
                throw new AssertionError("Unexpected GI stage for " + viewPort.getRenderPath() + " frame " + frame);
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int[] left = sample(pixels, -2);
            int[] right = sample(pixels, 2);
            int[] center = sample(pixels, 0);
            int[] edge = sample(pixels, 1.1f);
            if (left[0] < 90 || left[1] > 2 || right[0] < 90 || right[1] > 2
                    || center[1] < 90 || center[0] > 2 || edge[0] < 15 || edge[1] < 15) {
                throw new AssertionError("Incorrect regional GI selection: " + Arrays.toString(left)
                        + " / " + Arrays.toString(center) + " / " + Arrays.toString(right)
                        + " / edge " + Arrays.toString(edge));
            }
            int[] previous = left;
            for (int step = 0; step <= 80; step++) {
                int[] color = sample(pixels, -2 + step * 0.05f);
                if (Math.abs(color[0] + color[1] - 102) > 4
                        || Math.abs(color[0] - previous[0]) > 30 || Math.abs(color[1] - previous[1]) > 30) {
                    throw new AssertionError("Discontinuous or unnormalized overlap: " + Arrays.toString(color));
                }
                previous = color;
            }
            // 第四步：跨越两个边界等距线，验证混合不会在对角线上突然停止变化。
            int[] diagonalBefore = sample(pixels, -1.35f, 1.23f);
            int[] diagonalCenter = sample(pixels, -1.15f, 1.23f);
            int[] diagonalAfter = sample(pixels, -0.95f, 1.23f);
            if (diagonalCenter[1] - diagonalBefore[1] < 2
                    || diagonalAfter[1] - diagonalCenter[1] < 2) {
                throw new AssertionError("Nearest-face ridge in overlap: "
                        + Arrays.toString(diagonalBefore) + " / " + Arrays.toString(diagonalCenter)
                        + " / " + Arrays.toString(diagonalAfter));
            }
            int[] outside = sample(pixels, 3.75f);
            if (outside[0] + outside[1] > 2) {
                throw new AssertionError("GI escaped both coverage bounds");
            }
            System.out.println("GI REGIONS " + viewPort.getRenderPath() + " PASS "
                    + (frame % 64 < 32 ? "automatic " : "explicit ") + "lighting=" + lightingPayload
                    + " " + Arrays.toString(center));
            BufferUtils.destroyDirectBuffer(pixels);
            if (frame == 127) {
                if (halfRegression) {
                    System.out.println("HALF_GI_SWITCH_PASS max=" + maximumDifference + " approximateMax=" + approximateDifference + " restored=0 odd=241x181");
                }
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
        int pixel = ((int) screen.y * cam.getWidth() + (int) screen.x) * 4;
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
        if (invalid != null) {
            invalid.dispose();
        }
        super.destroy();
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
