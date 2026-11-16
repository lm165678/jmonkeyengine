package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.*;
import com.jme3.light.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;

/**
 * Shadowed irradiance capture regression, entry main, no input controls.
 * Requires OpenGL 3.3 float targets; runs on an offscreen context.
 * Verification: {@code gradlew :jme3-examples:giProbeShadowSmoke}.
 * A roof blocks directional, point and spot light from reaching a diffuse floor.
 * Compares actual baked SH energy with/without capture shadows, not screenshots
 * or probe-visibility weights. APIs: GiSceneBaker, Spatial.setShadowMode and
 * GiProbeData.evaluateProbeIrradiance. No FPP is required.
 */
public final class TestGiProbeShadows extends SimpleApplication {
    public static void main(String[] args) {
        TestGiProbeShadows app = new TestGiProbeShadows();
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
        // 第一步：接收与遮挡模式和运行时场景一致；不使用后处理 FPP。
        rootNode.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        surface("floor", new Vector3f(4, 0.1f, 4), new Vector3f(0, -0.1f, 0));
        surface("roof", new Vector3f(4, 0.1f, 4), new Vector3f(0, 2, 0));
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(1, 0.5f, 1), 2, 2, 2);
        volume.setLocalTranslation(-0.5f, 0.5f, -0.5f);
        rootNode.attachChild(volume);
        DirectionalLight sun = new DirectionalLight(new Vector3f(0, -1, 0), ColorRGBA.White);
        PointLight point = new PointLight(new Vector3f(0, 4, 0), ColorRGBA.White, 20);
        SpotLight spot = new SpotLight();
        spot.setPosition(new Vector3f(0, 4, 0));
        spot.setDirection(new Vector3f(0, -1, 0));
        spot.setSpotRange(20);
        spot.setSpotInnerAngle(0.5f);
        spot.setSpotOuterAngle(0.7f);
        for (Light light : new Light[]{sun, point, spot}) {
            rootNode.addLight(light);
            rootNode.updateGeometricState();
            GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
            quality.captureSize = 16;
            quality.radianceSamples = 256;
            quality.visibilitySamples = 16;
            quality.far = 20;
            // 第二步：对照组显式关闭捕获阴影；默认设置则会自动创建阴影处理器。
            quality.captureShadows = false;
            GiProbeData unshadowed = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                    capture -> { }, () -> false, completed -> { });
            quality.captureShadows = true;
            GiProbeData shadowed = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                    capture -> { }, () -> false, completed -> { });
            // 第三步：直接检查 SH 中向下的辐照度，避免把运行时距离遮挡误认为烘焙阴影。
            float before = energy(unshadowed);
            float after = energy(shadowed);
            // 第四步：灯的场景投影开关关闭后，捕获恢复无阴影的直接光。
            light.setCastShadow(false);
            GiProbeData disabled = GiSceneBaker.bake(renderManager, rootNode, volume, quality,
                    capture -> { }, () -> false, completed -> { });
            if (Math.abs(energy(disabled) - before) > before * 0.01f) {
                throw new AssertionError("Light castShadow preference ignored");
            }
            disabled.dispose();
            System.out.println("GI SHADOW " + light.getType() + " unshadowed=" + before + " shadowed=" + after);
            if (before < 1 || after >= before * 0.4f) {
                throw new AssertionError("Captured radiance did not include " + light.getType() + " shadows");
            }
            unshadowed.dispose();
            shadowed.dispose();
            rootNode.removeLight(light);
        }
        // 第五步：被遮挡的红灯不能压暗绿灯和蓝色环境光的烘焙贡献。
        sun.setColor(new ColorRGBA(1, 0, 0, 1));
        sun.setCastShadow(true);
        sun.setEnabled(false);
        rootNode.addLight(sun);
        DirectionalLight fill = new DirectionalLight(new Vector3f(0, -1, 0), new ColorRGBA(0, 1, 0, 1));
        fill.setCastShadow(false);
        rootNode.addLight(fill);
        rootNode.addLight(new AmbientLight(new ColorRGBA(0, 0, 0.2f, 1)));
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                ((Geometry) spatial).getMaterial().setColor("Ambient", ColorRGBA.White);
            }
        });
        rootNode.updateGeometricState();
        GiSceneBaker.Settings mixedQuality = new GiSceneBaker.Settings();
        mixedQuality.captureSize = 16;
        mixedQuality.radianceSamples = 256;
        mixedQuality.visibilitySamples = 16;
        mixedQuality.far = 20;
        GiProbeData baseline = GiSceneBaker.bake(renderManager, rootNode, volume, mixedQuality,
                capture -> { }, () -> false, completed -> { });
        sun.setEnabled(true);
        GiProbeData mixed = GiSceneBaker.bake(renderManager, rootNode, volume, mixedQuality,
                capture -> { }, () -> false, completed -> { });
        Vector3f a = baseline.evaluateProbeIrradiance(0, new Vector3f(0, -1, 0), new Vector3f());
        Vector3f b = mixed.evaluateProbeIrradiance(0, new Vector3f(0, -1, 0), new Vector3f());
        if (a.y < 0.1f || a.z < 0.1f || Math.abs(a.y - b.y) > a.y * 0.02f
                || Math.abs(a.z - b.z) > a.z * 0.02f || b.x > 0.02f) {
            throw new AssertionError("Baked shadow affected unrelated light: " + a + " -> " + b);
        }
        baseline.dispose();
        mixed.dispose();
        System.out.println("GI MULTI-LIGHT ISOLATION PASS " + a + " -> " + b);
        System.out.println("GI SHADOW SMOKE PASS");
        stop();
    }

    private float energy(GiProbeData data) {
        float sum = 0;
        Vector3f value = new Vector3f();
        for (int p = 0; p < data.getGrid().getProbeCount(); p++) {
            sum += data.evaluateProbeIrradiance(p, new Vector3f(0, -1, 0), value).x;
        }
        return sum;
    }

    private void surface(String name, Vector3f halfSize, Vector3f position) {
        Geometry geometry = new Geometry(name, new Box(halfSize.x, halfSize.y, halfSize.z));
        Material material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setBoolean("UseMaterialColors", true);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Ambient", ColorRGBA.Black);
        material.setColor("Specular", ColorRGBA.Black);
        geometry.setMaterial(material);
        geometry.setLocalTranslation(position);
        rootNode.attachChild(geometry);
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
