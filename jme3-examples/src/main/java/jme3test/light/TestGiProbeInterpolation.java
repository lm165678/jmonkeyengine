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
 * GPU regression for low-confidence GI interpolation, entry {@link #main}.
 * Requires OpenGL 4.3; no input controls. Run
 * {@code gradlew :jme3-examples:giProbeInterpolationSmoke}.
 * Uniform irradiance must stay uniform when positive visibility weights become
 * small. Strict mixing keeps exactly occluded probes black; stabilized mixing
 * deliberately retains a visible fallback, quantifying its possible leakage.
 * Invalid probes remain excluded in both modes. Tests Lighting and PBR on
 * all four render paths. Synthetic moments deliberately isolate interpolation
 * from capture quality; compact moment cases reproduce cancellation while
 * exact zero variance must stay occluded. These synthetic inputs are not an
 * application bake configuration.
 * Bindings and texture changes are render-thread-only; close bindings before
 * disposing data. Old assets retain legacy decoding; rebake to recover precision
 * previously lost when storing distance moments in half floats.
 */
public final class TestGiProbeInterpolation extends SimpleApplication {
    private static final RenderPath[] PATHS = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};
    private static final float[] VARIANCES = {1f, 0.001f, 0.0001f, 0f, 0.000001f, 0f, 1f, 1f};
    private Geometry receiver;
    private GiProbeData data;
    private GiProbeData.Binding binding;
    private int frame;
    private int baseline;

    public static void main(String[] args) {
        TestGiProbeInterpolation app = new TestGiProbeInterpolation();
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
        receiver = new Geometry("GI receiver", new Box(1, 0.1f, 1));
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
        int variant = phase % VARIANCES.length;
        viewPort.setRenderPath(PATHS[(phase / VARIANCES.length) % 4]);
        boolean pbr = (phase / (VARIANCES.length * 4)) % 2 == 1;
        boolean stabilized = phase >= VARIANCES.length * 8;
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
        receiver.setLocalTranslation(0, variant == 6 ? -2.1f : 0, 0);
        binding = data.bind(material, new Vector3f(-2, -2, -2), variant == 6 ? 0.05f : 0);
        // 稳定混合通常通过 volume.setStabilizedBlending(true) 自动接入。
        // 此底层测试直接修改描述符位，以隔离采样 shader；false 恢复旧模式。
        if (stabilized) {
            Vector4f atlasInfo = ((Vector4f) material.getParam("GiAtlas").getValue()).clone();
            atlasInfo.z += 0.125f;
            material.setVector4("GiAtlas", atlasInfo);
        }
        // 第二步：仅替换测试距离矩，从正常权重连续降低到极小权重和完全遮挡。
        ByteBuffer bytes = BufferUtils.createByteBuffer(16);
        Image.Format format = Image.Format.RGBA32F;
        if (variant == 4 || variant == 5) {
            // 第三步：复现半精度二阶矩相减为负；独立标准差应保留有效可见度。
            float mean = 0.045f;
            bytes.putShort(FastMath.convertFloatToHalf(mean));
            bytes.putShort(FastMath.convertFloatToHalf(mean * mean + VARIANCES[variant]));
            bytes.putShort(FastMath.convertFloatToHalf((float) Math.sqrt(VARIANCES[variant])));
            bytes.putShort(FastMath.convertFloatToHalf(1));
            format = Image.Format.RGBA16F;
        } else {
            bytes.putFloat(0).putFloat(VARIANCES[variant]).putFloat(0).putFloat(0);
        }
        bytes.flip();
        Texture2D moments = new Texture2D(new Image(format, 1, 1, bytes, ColorSpace.Linear));
        material.setTexture("GiMoments", moments);
        if (variant == 6 || variant == 7) {
            // 第四步：接收面恰在网格边界，下层探针无效；偏移后应使用上层有效探针。
            Texture2D original = (Texture2D) material.getTextureParam("GiIrradiance").getTextureValue();
            Image image = original.getImage();
            ByteBuffer copy = BufferUtils.createByteBuffer(image.getData(0).capacity());
            copy.put(image.getData(0).duplicate().clear());
            copy.clear();
            int columns = image.getWidth() / 9;
            for (int probe = 0; probe < 8; probe++) {
                if (variant == 7 || (probe / 2) % 2 == 0) {
                    int pixel = (probe / columns) * image.getWidth() + (probe % columns) * 9;
                    copy.putFloat(pixel * 16 + 12, 0);
                }
            }
            material.setTexture("GiIrradiance", new Texture2D(new Image(Image.Format.RGBA32F,
                    image.getWidth(), image.getHeight(), copy, ColorSpace.Linear)));
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 3 == 2) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
            int value = pixels.get((32 * 64 + 32) * 4) & 255;
            int variant = frame / 3 % VARIANCES.length;
            boolean stabilized = frame / 3 >= VARIANCES.length * 8;
            boolean expectBlack = variant == 7 || (!stabilized && (variant == 3 || variant == 5));
            if (variant == 0) {
                baseline = value;
                if (baseline < 20) {
                    throw new AssertionError("Missing reference GI: " + value);
                }
            } else if (expectBlack ? value > 1 : Math.abs(value - baseline) > 2) {
                throw new AssertionError("GI normalization discontinuity: phase=" + frame / 3
                        + " reference=" + baseline + " value=" + value);
            }
            System.out.println("GI_INTERPOLATION phase=" + frame / 3 + " value=" + value);
            // 第三步：恢复绑定，确认精确零可见度没有因修复产生漏光。
            if (frame == VARIANCES.length * 4 * 2 * 2 * 3 - 1) {
                binding.close();
                data.dispose();
                System.out.println("GI_INTERPOLATION_PASS");
                stop();
            }
        }
        frame++;
    }
}
