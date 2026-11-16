package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.GiBakeJob;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.GiSceneBaker;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Runnable diffuse multi-bounce GPU regression. Requires OpenGL 4.3 and floating
 * point capture targets; entry main, no input controls. Run
 * {@code gradlew :jme3-examples:giMultipleBouncesSmoke}; completion prints
 * GI_MULTIPLE_BOUNCES_PASS. Add {@code -PgiPbr} to check PBR materials.
 * Uses an emissive ceiling patch and diffuse walls;
 * later passes must increase returned energy without amplifying the emitter or
 * feeding back the volume's display intensity. Checks cancellation, state
 * restoration, settings snapshots and compressed-data persistence.
 * A scene-wide visual test remains necessary; this does not assert universal
 * absence of interpolation artifacts or exact path-traced transport.
 * <pre>{@code
 * // 第一步：创建漫反射场景及 GI Volume；普通材质仍使用自带的 .j3md。
 * GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
 * // 第二步：默认 1 次；显式选择 3 次反射，运行时仍为八探针。
 * quality.bounceCount = 3;
 * GiBakeJob job = GiSceneBaker.start(renderManager, rootNode, volume, quality,
 *         view -> { }, () -> false, count -> { });
 * // 第三步：渲染线程每帧推进；所有轮次成功后才保存最终 J3O。
 * if (job.step(2_000_000L)) {
 *     GiProbeData result = job.getResult();
 *     job.close();
 * }
 * // 第四步：取消调用 close，旧数据保持可用。多次反射暂不支持断点续烤。
 * // 恢复一次反射：将 bounceCount 设回 1 后重新烘焙。
 * }</pre>
 */
public final class TestGiMultipleBounces extends SimpleApplication {
    private Material diffuse;

    public static void main(String[] args) {
        TestGiMultipleBounces app = new TestGiMultipleBounces();
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
        getSceneServices().setEnabled(false);
        if (Boolean.getBoolean("gi.test.pbr")) {
            diffuse = new Material(assetManager, "Common/MatDefs/Light/PBRLighting.j3md");
            diffuse.setColor("BaseColor", new ColorRGBA(0.6f, 0.6f, 0.6f, 1));
            diffuse.setFloat("Metallic", 0);
            diffuse.setFloat("Roughness", 1);
        } else {
            diffuse = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
            diffuse.setBoolean("UseMaterialColors", true);
            diffuse.setColor("Diffuse", new ColorRGBA(0.6f, 0.6f, 0.6f, 1));
            diffuse.setColor("Ambient", ColorRGBA.Black);
            diffuse.setColor("Specular", ColorRGBA.Black);
        }
        box("floor", new Vector3f(0, -0.1f, 0), new Vector3f(2, 0.1f, 2), diffuse);
        box("ceiling", new Vector3f(0, 4.1f, 0), new Vector3f(2, 0.1f, 2), diffuse);
        box("left", new Vector3f(-2.1f, 2, 0), new Vector3f(0.1f, 2, 2), diffuse);
        box("right", new Vector3f(2.1f, 2, 0), new Vector3f(0.1f, 2, 2), diffuse);
        box("back", new Vector3f(0, 2, -2.1f), new Vector3f(2, 2, 0.1f), diffuse);
        box("front", new Vector3f(0, 2, 2.1f), new Vector3f(2, 2, 0.1f), diffuse);
        Material emitter = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        emitter.setColor("Color", new ColorRGBA(4, 4, 4, 1));
        box("emitter", new Vector3f(0, 3.8f, 0), new Vector3f(0.6f, 0.05f, 0.6f), emitter);
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(4.4f, 4.4f, 4.4f), 4, 4, 4);
        volume.setLocalTranslation(-2.2f, -0.2f, -2.2f);
        rootNode.attachChild(volume);
        rootNode.updateGeometricState();
        GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
        quality.captureSize = 32;
        quality.radianceSamples = 1024;
        quality.visibilityResolution = 8;
        quality.visibilitySamples = 16;
        quality.compactStorage = false;
        GiProbeData first = bake(volume, quality);
        float firstEnergy = energy(first);
        quality.bounceCount = 3;
        try (GiBakeJob cancelled = GiSceneBaker.start(renderManager, rootNode, volume, quality,
                view -> { }, () -> false, count -> { })) {
            while (cancelled.getCompletedProbeCount() < 64) {
                cancelled.step(1);
            }
            cancelled.step(1);
            cancelled.step(1);
            require(volume.getData() == first, "Intermediate pass was published");
            require(diffuse.getParam("GiIrradiance") == null, "Capture binding escaped its step");
            boolean rejected = false;
            try {
                cancelled.saveCheckpoint(new ByteArrayOutputStream());
            } catch (UnsupportedOperationException expected) {
                rejected = true;
            } catch (java.io.IOException error) {
                throw new AssertionError(error);
            }
            require(rejected, "Multi-bounce checkpoint was silently accepted");
        }
        require(volume.getData() == first, "Cancellation replaced old GI");
        GiProbeData third;
        try (GiBakeJob job = GiSceneBaker.start(renderManager, rootNode, volume, quality,
                view -> { }, () -> false, count -> { })) {
            quality.bounceCount = 1;
            while (!job.step(2_000_000L)) {
                require(diffuse.getParam("GiIrradiance") == null, "Material was not restored");
            }
            require(job.getCompletedProbeCount() == 192, "Settings were not snapshotted");
            third = job.getResult();
        }
        float thirdEnergy = energy(third);
        require(thirdEnergy > firstEnergy * 1.05f && thirdEnergy < firstEnergy * 8,
                "Missing indirect propagation or runaway emitter feedback: " + firstEnergy + " -> " + thirdEnergy);
        volume.setIntensity(20);
        quality.bounceCount = 3;
        GiProbeData repeated = bake(volume, quality);
        require(Math.abs(energy(repeated) - thirdEnergy) < 0.0001f,
                "Display intensity or old baked data fed back into capture");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            repeated.saveCompressed(bytes);
            GiProbeData restored = GiProbeData.loadCompressed(new ByteArrayInputStream(bytes.toByteArray()));
            require(Math.abs(energy(restored) - energy(repeated)) < 0.0001f, "Persistence lost bounced energy");
            restored.dispose();
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
        require(diffuse.getParam("GiIrradiance") == null && emitter.getParam("GiIrradiance") == null,
                "Final capture retained material bindings");
        first.dispose();
        third.dispose();
        repeated.dispose();
        System.out.println("GI_MULTIPLE_BOUNCES_PASS first=" + firstEnergy + " third=" + thirdEnergy);
        stop();
    }

    private GiProbeData bake(GiProbeVolume volume, GiSceneBaker.Settings quality) {
        return GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                view -> { }, () -> false, count -> { });
    }

    private float energy(GiProbeData data) {
        float sum = 0;
        for (int probe = 0; probe < data.getGrid().getProbeCount(); probe++) {
            sum += data.evaluateProbeIrradiance(probe, Vector3f.UNIT_Y, new Vector3f()).x;
        }
        return sum;
    }

    private void box(String name, Vector3f position, Vector3f size, Material material) {
        Geometry geometry = new Geometry(name, new Box(size.x, size.y, size.z));
        geometry.setMaterial(material);
        geometry.setLocalTranslation(position);
        rootNode.attachChild(geometry);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
