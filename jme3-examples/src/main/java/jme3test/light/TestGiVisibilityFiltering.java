package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Visibility-filtering GPU regression: unequal irradiance with foreground and
 * background depth distributions, an analytic grazing-angle planar receiver
 * with an 8x8 visibility map, and bright probes
 * behind the receiving plane (strict and stabilized blending). A tangential sweep
 * near front-side probes rejects projection-centered interpolation hotspots. Requires OpenGL 4.3 and floating point targets.
 * Entry main; no controls. Tests Lighting/PBR on four render paths.
 * Run {@code gradlew :jme3-examples:giVisibilityFilteringSmoke}; GI_VISIBILITY_PASS
 * indicates completion. Uses material declarations; synthetic data binding below
 * is a low-level test fixture, not normal application setup.
 * This strict visibility regression explicitly selects the optional wide mode.
 * Visibility is filtered with sixteen positive cubic taps. The checkerboard
 * depth case verifies that filtering retains partial visibility without letting
 * foreground/background moments manufacture variance; fully blocked probes
 * remain blocked. The normal hemisphere and spatial-stencil tests must also
 * remain valid when the angular support is widened.
 * The companion {@code giCompactVisibilitySmoke} task retains eight probes and
 * four moment taps. It checks planar illumination and fully blocked probes on
 * the same four paths and two materials. The compact moment filter can still
 * merge foreground/background distributions; it does not claim the strict
 * checkerboard occlusion guarantee of the wide reference mode.
 * Receiver-plane correction uses triangle geometry, while normal maps continue
 * to affect irradiance. Existing bakes and the default eight-probe mode remain
 * usable without new application setup or changes to saved intensity.
 * <pre>
 * 第一步：普通应用加载包含 GI 的 J3O，自动场景服务会接入可见性过滤。
 * 第二步：无需平滑开关，也无需重烘焙；关闭 GI 则恢复没有体积间接光的结果。
 * 第三步：本例在渲染线程构造近远深度交界，验证遮挡不会借用背景深度。
 * 第四步：面背后探针即使距离图判断可见也不得贡献；普通模式和稳定模式均验证。
 * 第五步：旧平滑标志不再启用漏光权重。绑定在渲染线程释放。
 * 第六步：运行 giCompactVisibilitySmoke 检查默认八探针的平面照明和完整遮挡。
 * 第七步：原场景保持八探针及原亮度，无需重新烘焙；模型法线贴图仍用于照明。
 * </pre>
 */public final class TestGiVisibilityFiltering extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private Geometry receiver;
    private GiProbeData data;
    private GiProbeData.Binding binding;
    private int frame;
    private static final int PLANE_STEPS = 17;
    private final int[] planeValues = new int[PLANE_STEPS];
    private final int[] values = new int[7];
    private static final boolean COMPACT = Boolean.getBoolean("gi.test.compact");

    public static void main(String[] args) {
        TestGiVisibilityFiltering app = new TestGiVisibilityFiltering();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(64, 64);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // Low-level GI ownership test; ordinary loaded scenes use automatic services.
        getSceneServices().setEnabled(false);
        // 第一步：先烘焙均匀环境，保证八个探针具有相同的有效光照。
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(4, 4, 4), 2, 2, 2);
        volume.setLocalTranslation(-2, -2, -2);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
        quality.captureSize = 8;
        quality.radianceSamples = 256;
        quality.visibilityResolution = 4;
        quality.visibilitySamples = 4;
        quality.compactStorage = false;
        quality.separateSky = false;
        quality.background = new ColorRGBA(0.4f, 0.4f, 0.4f, 1);
        data = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                view -> { }, () -> false, count -> { });
        receiver = new Geometry("GI receiver", new Box(3, 0.1f, 3));
        rootNode.attachChild(receiver);
        cam.setLocation(new Vector3f(0, 3, 0));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Z);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (frame % 3 != 0) {
            return;
        }
        int phase = frame / 3;
        boolean planeSweep = phase >= 56;
        int planeCase = (phase - 56) / PLANE_STEPS;
        int planeStep = (phase - 56) % PLANE_STEPS;
        int variant = planeSweep ? 0 : phase % 7;
        viewPort.setRenderPath(PATHS[planeSweep ? planeCase % 4 : (phase / 7) % 4]);
        boolean pbr = planeSweep ? planeCase % 8 >= 4 : phase >= 28;
        if (planeSweep) {
            // Near-plane probes must not cause projection-centered hotspots.
            float x = -1.8f + planeStep * 3.6f / (PLANE_STEPS - 1);
            cam.setLocation(new Vector3f(x, 3, 1.8f));
            cam.lookAt(new Vector3f(x, 0, 1.8f), Vector3f.UNIT_Z);
        }
        if (binding != null) {
            binding.close();
        }
        Material material = new Material(assetManager, pbr
                ? "Common/MatDefs/Light/PBRLighting.j3md" : "Common/MatDefs/Light/Lighting.j3md");
        if (pbr) {
            material.setFloat("Metallic", 0);
            material.setFloat("Roughness", 1);
        } else {
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", ColorRGBA.White);
            material.setColor("Ambient", ColorRGBA.Black);
            material.setColor("Specular", ColorRGBA.Black);
        }
        receiver.setMaterial(material);
        binding = data.bind(material, new Vector3f(-2, planeSweep || variant == 4 ? -3.88f : -2, -2), 0);
        Vector4f atlas = ((Vector4f) material.getParam("GiAtlas").getValue()).clone();
        if (variant >= 2) {
            atlas.z += 0.03125f;
        }
        material.setVector4("GiAtlas", atlas);
        Image source = material.getTextureParam("GiIrradiance").getTextureValue().getImage();
        ByteBuffer sh = BufferUtils.createByteBuffer(source.getData(0).capacity());
        sh.put(source.getData(0).duplicate().clear()).clear();
        int columns = source.getWidth() / 9;
        int resolution = variant == 4 ? 8 : 4;
        atlas.z = (COMPACT ? 0f : 0.015625f) + resolution + (variant >= 2 ? 0.03125f : 0f) + (variant == 6 || (planeSweep && planeCase >= 8) ? 0.125f : 0f);
        material.setVector4("GiAtlas", atlas);
        int width = columns * resolution;
        int height = source.getHeight() * resolution;
        ByteBuffer moments = BufferUtils.createByteBuffer(width * height * 16);
        for (int probe = 0; probe < 8; probe++) {
            boolean bright = variant >= 5 ? (probe / 2) % 2 == 0 : probe % 2 == 0;
            for (int c = 0; c < 9; c++) {
                int pixel = (probe / columns * source.getWidth() + probe % columns * 9 + c) * 16;
                for (int channel = 0; channel < 3; channel++) {
                    sh.putFloat(pixel + channel * 4, sh.getFloat(pixel + channel * 4) * (bright ? 1f : 0.1f));
                }
            }
            float mean = bright && (variant == 1 || variant == 3) ? 0 : 100;
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    int pixel = ((probe / columns * resolution + y) * width + probe % columns * resolution + x) * 16;
                    float tapMean = bright && variant == 2 ? ((x + y) % 2 == 0 ? 0 : 100) : mean;
                    if (bright && variant == 4) {
                        // Analytic flat receiver, no occluder. Upper probes sit
                        // only 0.02 above the plane. The old +/-25% projection
                        // clamp falsely shadows it (19 instead of reference 57).
                        // Adjacent angular rays hit at very different distances;
                        // increasing map resolution must not be the remedy.
                        float ox = 2f * (x + 0.5f) / resolution - 1f;
                        float oy = 2f * (y + 0.5f) / resolution - 1f;
                        Vector3f direction = new Vector3f(ox, oy, 1f - Math.abs(ox) - Math.abs(oy));
                        if (direction.z < 0f) {
                            direction.x = (1f - Math.abs(oy)) * (ox >= 0f ? 1f : -1f);
                            direction.y = (1f - Math.abs(ox)) * (oy >= 0f ? 1f : -1f);
                        }
                        direction.normalizeLocal();
                        float probeY = -3.88f + ((probe / 2) % 2) * 4f;
                        float distance = (0.1f - probeY) / direction.y;
                        float scale = ((Vector2f) material.getParam("GiEncoding").getValue()).x;
                        tapMean = distance > 0f ? distance / scale : 100f;
                    }
                    moments.putFloat(pixel, tapMean);
                    moments.putFloat(pixel + 4, tapMean * tapMean);
                    moments.putFloat(pixel + 8, 0);
                    moments.putFloat(pixel + 12, 1);
                }
            }
        }
        material.setTexture("GiIrradiance", new Texture2D(new Image(Image.Format.RGBA32F,
                source.getWidth(), source.getHeight(), sh, ColorSpace.Linear)));
        material.setTexture("GiMoments", new Texture2D(new Image(Image.Format.RGBA32F,
                width, height, moments, ColorSpace.Linear)));
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int value = pixels.get((32 * 64 + 32) * 4) & 255;
            if (frame / 3 >= 56) {
                int step = (frame / 3 - 56) % PLANE_STEPS;
                planeValues[step] = value;
                if (step == PLANE_STEPS - 1) {
                    int curvature = 0;
                    for (int i = 1; i < PLANE_STEPS - 1; i++) {
                        curvature = Math.max(curvature, Math.abs(planeValues[i - 1]
                                - 2 * planeValues[i] + planeValues[i + 1]));
                    }
                    System.out.println("GI_PLANE_SWEEP curvature=" + curvature
                            + " values=" + java.util.Arrays.toString(planeValues));
                    if (curvature > 2 || planeValues[0] - planeValues[PLANE_STEPS - 1] < 50) {
                        throw new AssertionError("Near-plane probe hotspot or lost irradiance gradient");
                    }
                }
                if (frame == (56 + 16 * PLANE_STEPS) * 3 - 1) {
                    binding.close();
                    data.dispose();
                    System.out.println("GI_VISIBILITY_PASS");
                    stop();
                }
                frame++;
                return;
            }
            int variant = frame / 3 % 7;
            values[variant] = value;
            if (variant == 6) {
                System.out.println("GI_VISIBILITY values=" + java.util.Arrays.toString(values));
                if (COMPACT) {
                    if (values[0] < 40 || Math.abs(values[1] - values[3]) > 1
                            || values[1] >= values[0] / 2 || Math.abs(values[4] - values[0]) > 2) {
                        throw new AssertionError("Compact visibility lost planar illumination or blocker rejection");
                    }
                    if (frame == 56 * 3 - 1) {
                        binding.close();
                        data.dispose();
                        System.out.println("GI_COMPACT_VISIBILITY_PASS");
                        stop();
                    }
                } else if (values[0] < 40 || Math.abs(values[1] - values[3]) > 1
                        || values[2] <= values[1] || values[2] >= values[0] - 10
                        || Math.abs(values[4] - values[0]) > 2
                        || Math.abs(values[5] - values[1]) > 1 || Math.abs(values[6] - values[1]) > 1) {
                    throw new AssertionError("Foreground/background visibility filtering failed");
                }
            }
        }
        frame++;
    }
}

