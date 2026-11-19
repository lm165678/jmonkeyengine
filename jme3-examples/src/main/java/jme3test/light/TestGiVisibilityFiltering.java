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
 * four moment reads with bilinear probability filtering. It checks planar
 * illumination, mixed foreground/background and fully blocked probes on all
 * four paths and two materials. Geometric receiver-plane bias and binary16
 * depth tolerance avoid self-shadowing without mixing unrelated distributions.
 * This finite angular filter is not a guarantee against all thin-wall leaks.
 * These synthetic cases do not validate curved receivers or a full scene's
 * indirect-light energy. Compare actual scene captures with matching shadow,
 * stable-blending and post-processing settings before accepting shader changes.
 * Run {@code gradlew :jme3-examples:giConfidenceContinuitySmoke}, or this entry
 * with {@code -Dgi.test.confidenceSweep=true}, to sweep partial
 * visibility over 33 steps with unequal probe energies on all four paths and
 * both material models. GI_CONFIDENCE_CONTINUITY_PASS verifies monotone, gradual
 * stabilized response rather than a dark plateau followed by a steep rise.
 * Existing bakes and the default eight-probe mode remain usable.
 * Volume diffuse evaluation applies an angular heat-kernel window (SH bands
 * 1 and 2 scaled by 0.5 and 0.125). The constant band, visibility moments and
 * sky visibility are unchanged. This smooths directional contrast, not spatial
 * occlusion. Bounce capture bypasses the window to avoid repeated filtering.
 * Raw CPU evaluateProbeIrradiance remains the unwindowed data diagnostic.
 * Optional compact receiver-plane projection fades with interpolated mesh-normal
 * variation over the angular moment footprint. Planar receivers retain their
 * correction; material normal maps do not control the fade. This heuristic is
 * not exact curved-surface transport and requires smooth mesh normals. Strict
 * zero-visibility rejection and the four moment reads are retained.
 * The compact path retains four moment reads per probe. Increasing the filter
 * to sixteen taps was rejected for its frame cost and remaining scene artifacts.
 * A passing synthetic test does not establish correctness of the baked field.
 * <pre>
 * 第一步：普通应用加载包含 GI 的 J3O，自动场景服务会接入可见性过滤。
 * 第二步：无需平滑开关，也无需重烘焙；关闭 GI 则恢复没有体积间接光的结果。
 * 第三步：本例在渲染线程构造近远深度交界，验证遮挡不会借用背景深度。
 * 第四步：面背后探针即使距离图判断可见也不得贡献；普通模式和稳定模式均验证。
 * 第五步：旧平滑标志不再启用漏光权重。绑定在渲染线程释放。
 * 第六步：运行 giCompactVisibilitySmoke 检查默认八探针的平面照明和完整遮挡。
 * 第七步：原场景保持八探针，无需重新烘焙；方向低通保留平均能量，但会改变局部明暗。
 * 第八步：运行时自动应用方向低通，天空可见性及多次反射捕获不受此滤波影响。
 * 第九步：关闭 GI 可恢复无体积间接光的对照；滤波没有单独开关，已有烘焙文件不被修改。
 * 第十步：默认保持八探针、每探针四次距离图采样；对照真实场景的遮挡、
 *         接触处及 GPU 耗时，勿将增加采样或提升亮度作为通过标准。
 * 第十一步：启用 volume.setStabilizedBlending(true)，八探针使用连续可见性置信度；
 *         无需重烘焙，不增加距离图读取；在渲染线程恢复 false 可回到严格遮挡模式。
 *         稳定模式保留少量被遮挡探针贡献，需同时检查薄墙漏光；宽邻域和表面布局保持原行为。
 * 第十二步：加载旧烘焙数据即可使用四采样可见性过滤，无需改变密度或重烘焙。
 *         几何平面只参与遮挡和偏移；材质法线贴图仍参与光照响应。
 *         重建引擎资源后部署到运行程序；此测试不修改场景文件。
 * 第十三步：默认关闭曲面修正以兼容旧方案；在渲染线程调用
 *         volume.setCurvedReceiverCorrection(true) 后光滑曲面限制平面投影，无需重烘焙。
 *         调用 setCurvedReceiverCorrection(false) 恢复旧方案；保存 J3O 保留此选项。
 *         比较原平面墙体、拱顶和掠射角，保留原八探针四采样配置。
 *         自定义 shader 应传入 giSampleIrradiance(position, shadingNormal, geometryNormal)，
 *         第三个参数为归一化的原始网格法线；旧二参数入口仍可用。
 *         恢复旧 shader 资源可撤回此运行时行为，烘焙格式保持兼容。
 * </pre>
 */
public final class TestGiVisibilityFiltering extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private static final boolean CONFIDENCE_SWEEP = Boolean.getBoolean("gi.test.confidenceSweep");
    private static final int CONFIDENCE_STEPS = 33;
    private final int[] confidenceValues = new int[CONFIDENCE_STEPS];
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
        if (CONFIDENCE_SWEEP) {
            updateConfidenceSweep();
            return;
        }
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
        if (CONFIDENCE_SWEEP) {
            readConfidenceSweep();
            return;
        }
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int value = pixels.get((32 * 64 + 32) * 4) & 255;
            if (COMPACT) {
                // A flat synthetic receiver has no isolated one-pixel occluders.
                // Catch negative cubic weights/NaNs near angular texel borders,
                // which a single center-pixel assertion can miss.
                for (int y = 8; y < 56; y++) {
                    for (int x = 8; x < 56; x++) {
                        int center = pixels.get((y * 64 + x) * 4) & 255;
                        int adjacent = Math.min(
                                pixels.get((y * 64 + x - 1) * 4) & 255,
                                pixels.get((y * 64 + x + 1) * 4) & 255);
                        adjacent = Math.min(adjacent, pixels.get(((y - 1) * 64 + x) * 4) & 255);
                        adjacent = Math.min(adjacent, pixels.get(((y + 1) * 64 + x) * 4) & 255);
                        if (adjacent - center > 8) {
                            throw new AssertionError("Isolated compact visibility dropout at " + x + "," + y);
                        }
                    }
                }
            }
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
                            || values[1] >= values[0] / 2 || Math.abs(values[4] - values[0]) > 2
                            || values[2] <= values[1] || values[2] >= values[0] - 10) {
                        throw new AssertionError("Compact visibility lost planar illumination, partial visibility or blocker rejection");
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
    /** Sweep uncertainty while holding geometry and baked radiance fixed. */
    private void updateConfidenceSweep() {
        if (frame % 3 != 0) {
            return;
        }
        int phase = frame / 3;
        int step = phase % CONFIDENCE_STEPS;
        int scenario = phase / CONFIDENCE_STEPS;
        viewPort.setRenderPath(PATHS[scenario % 4]);
        if (binding != null) {
            binding.close();
        }
        Material material = new Material(assetManager, scenario < 4
                ? "Common/MatDefs/Light/Lighting.j3md" : "Common/MatDefs/Light/PBRLighting.j3md");
        if (scenario < 4) {
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", ColorRGBA.White);
            material.setColor("Ambient", ColorRGBA.Black);
            material.setColor("Specular", ColorRGBA.Black);
        } else {
            material.setColor("BaseColor", ColorRGBA.White);
            material.setFloat("Metallic", 0f);
            material.setFloat("Roughness", 1f);
        }
        receiver.setMaterial(material);
        binding = data.bind(material, new Vector3f(-2, -2, -2), 0);
        Vector4f atlas = ((Vector4f) material.getParam("GiAtlas").getValue()).clone();
        atlas.z = 4.125f;
        material.setVector4("GiAtlas", atlas);
        Image source = material.getTextureParam("GiIrradiance").getTextureValue().getImage();
        ByteBuffer sh = BufferUtils.createByteBuffer(source.getData(0).capacity());
        sh.put(source.getData(0).duplicate().clear()).clear();
        int columns = source.getWidth() / 9;
        int width = columns * 4;
        int height = source.getHeight() * 4;
        ByteBuffer moments = BufferUtils.createByteBuffer(width * height * 16);
        float probability = (float) step / (CONFIDENCE_STEPS - 1);
        float distanceScale = ((Vector2f) material.getParam("GiEncoding").getValue()).x;
        float squaredDistance = (4f + 1.9f * 1.9f + 4f) / (distanceScale * distanceScale);
        float variance = probability < 1f ? squaredDistance * probability / (1f - probability) : 0f;
        for (int probe = 0; probe < 8; probe++) {
            boolean bright = probe % 2 == 0;
            for (int c = 0; c < 9; c++) {
                int offset = (probe / columns * source.getWidth() + probe % columns * 9 + c) * 16;
                for (int channel = 0; channel < 3; channel++) {
                    sh.putFloat(offset + channel * 4,
                            sh.getFloat(offset + channel * 4) * (bright ? 1f : 0.1f));
                }
            }
            float mean = bright && probability < 1f ? 0f : 100f;
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    int offset = ((probe / columns * 4 + y) * width + probe % columns * 4 + x) * 16;
                    moments.putFloat(offset, mean);
                    moments.putFloat(offset + 4, mean * mean);
                    moments.putFloat(offset + 8, bright ? (float) Math.sqrt(variance) : 0f);
                    moments.putFloat(offset + 12, 1f);
                }
            }
        }
        material.setTexture("GiIrradiance", new Texture2D(new Image(Image.Format.RGBA32F,
                source.getWidth(), source.getHeight(), sh, ColorSpace.Linear)));
        material.setTexture("GiMoments", new Texture2D(new Image(Image.Format.RGBA32F,
                width, height, moments, ColorSpace.Linear)));
    }

    private void readConfidenceSweep() {
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int step = frame / 3 % CONFIDENCE_STEPS;
            confidenceValues[step] = pixels.get((32 * 64 + 32) * 4) & 255;
            if (step == CONFIDENCE_STEPS - 1) {
                int jump = 0;
                for (int i = 1; i < CONFIDENCE_STEPS; i++) {
                    if (confidenceValues[i] < confidenceValues[i - 1]) {
                        throw new AssertionError("Visibility confidence must not reverse illumination");
                    }
                    jump = Math.max(jump, confidenceValues[i] - confidenceValues[i - 1]);
                }
                int range = confidenceValues[CONFIDENCE_STEPS - 1] - confidenceValues[0];
                int mid = confidenceValues[CONFIDENCE_STEPS / 2] - confidenceValues[0];
                System.out.println("GI_CONFIDENCE jump=" + jump + " values="
                        + java.util.Arrays.toString(confidenceValues));
                if (range < 10 || jump > 3 || mid < range * 0.5f) {
                    throw new AssertionError("Stabilized blend crushes partial visibility into a dark plateau");
                }
            }
            if (frame == 8 * CONFIDENCE_STEPS * 3 - 1) {
                binding.close();
                data.dispose();
                System.out.println("GI_CONFIDENCE_CONTINUITY_PASS");
                stop();
            }
        }
        frame++;
    }

}
