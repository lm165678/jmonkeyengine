package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.SkyLightControl;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.RenderPath;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.system.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Entry main: deterministic HDR sky-only lighting for Phong and PBR in all four
 * rendering paths. Requires OpenGL 4.3 and HDR render targets; no input controls.
 * Verify with gradlew :jme3-examples:skyLightSmoke :jme3-examples:skyLightPngSmoke. No scene/SDK files are written.
 * SkyFactory installs SkyLightControl (128 default; 16 here for test speed).
 * Disable the control for background-only rendering. Request rebake after sky
 * texture changes and separately rebake any static GI volumes. Rendering and
 * lifecycle changes run on the application's render thread.
 * SkyLight.get/setIntensity scales diffuse and fallback reflections without
 * changing the background or recapturing maps. Default 1; 0 disables the
 * contribution. The final smoke phases check 0, 0.5 and restored 1 in every path.
 */
public final class TestSkyLight extends SimpleApplication {
    private int frames;
    private boolean png;
    private final int[][] baseline = new int[2][];
    private com.jme3.environment.gi.GiProbeData data;
    private com.jme3.environment.gi.GiProbeData.Binding binding;
    private Geometry target;
    private Spatial skySpatial;
    private SkyLightControl skyLighting;
    private com.jme3.light.LightProbe localReflection;
    private final RenderPath[] paths = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU};

    public static void main(String[] args) {
        TestSkyLight app = new TestSkyLight();
        app.png = args.length > 0 && args[0].equals("--png");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setResolution(64, 64);
        settings.setGammaCorrection(false);
        settings.setGpuSceneEnabled(true);
        settings.setGpuSceneBindlessEnabled(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // Low-level GI ownership test; ordinary loaded scenes use automatic services.
        getSceneServices().setEnabled(false);
        // 第一步：构造线性 HDR 天空，红色辐射大于 1，避免以 LDR 假装 HDR。
        ArrayList<ByteBuffer> faces = new ArrayList<>();
        for (int face = 0; face < 6; face++) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 16);
            for (int i = 0; i < 16; i++) {
                pixels.putFloat(2).putFloat(0.5f).putFloat(0.25f).putFloat(1);
            }
            pixels.flip();
            faces.add(pixels);
        }
        TextureCubeMap cube = new TextureCubeMap(new Image(Image.Format.RGBA32F,
                4, 4, 0, faces, ColorSpace.Linear));
        Spatial sky;
        if (png) {
            Texture[] images = new Texture[6];
            for (int i = 0; i < images.length; i++) {
                ByteBuffer pixels = BufferUtils.createByteBuffer(4 * 4 * 4);
                for (int j = 0; j < 16; j++) {
                    pixels.put((byte) 128).put((byte) 64).put((byte) 32).put((byte) 255);
                }
                pixels.flip();
                images[i] = new Texture2D(new Image(Image.Format.RGBA8, 4, 4, pixels, ColorSpace.sRGB));
            }
            sky = SkyFactory.createSky(assetManager, images[0], images[1], images[2],
                    images[3], images[4], images[5]);
        } else {
            sky = SkyFactory.createSky(assetManager, cube, SkyFactory.EnvMapType.CubeMap);
        }
        // 第二步：控制天空捕捉质量；普通帧不会重新烘焙。
        skyLighting = sky.getControl(SkyLightControl.class);
        skyLighting.setResolution(16);
        rootNode.attachChild(sky);
        skySpatial = sky;
        target = new Geometry("receiver", new Box(1, 1, 0.1f));
        rootNode.attachChild(target);
        cam.setLocation(new Vector3f(0, 0, 4));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        setDisplayFps(false);
        setDisplayStatView(false);
        selectMaterial(false);
    }

    private void selectMaterial(boolean pbr) {
        Material material = new Material(assetManager, pbr
                ? "Common/MatDefs/Light/PBRLighting.j3md" : "Common/MatDefs/Light/Lighting.j3md");
        if (pbr) {
            material.setColor("BaseColor", new ColorRGBA(0.25f, 0.25f, 0.25f, 1));
            material.setFloat("Metallic", 0);
            material.setFloat("Roughness", 1);
        } else {
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", new ColorRGBA(0.25f, 0.25f, 0.25f, 1));
            material.setColor("Specular", ColorRGBA.Black);
        }
        if (binding != null) {
            binding.close();
            binding = null;
        }
        target.setMaterial(material);
        // 预加载不提供 LightList；后续绘制必须重新选择有天光的 shader 变体。
        renderManager.preloadScene(target);
        if (data != null) {
            binding = data.bind(material, Vector3f.ZERO, 0.05f);
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (frames == 2) {
            skySpatial.setCullHint(Spatial.CullHint.Always);
        }
        if (frames == 4) {
            skySpatial.setCullHint(Spatial.CullHint.Never);
        }
        // 第三步：切换管线与材质，最后禁用天光验证恢复为黑色。
        if (frames % 4 == 0 && frames < 32) {
            viewPort.setRenderPath(paths[(frames / 4) % 4]);
            selectMaterial(frames >= 16);
        }
        if (frames == 32) {
            skyLighting.setEnabled(false);
        }
        if (frames == 36) {
            skyLighting.setEnabled(true);
            Vector3f e = com.jme3.environment.gi.IrradianceSh.evaluate(
                    com.jme3.environment.gi.IrradianceSh.fromLightProbe(skyLighting.getLight().getShCoeffs()), Vector3f.UNIT_Y);
            float expected = png ? (float) Math.pow((128 / 255f + 0.055) / 1.055, 2.4) : 2f;
            if (Math.abs(e.x / FastMath.PI - expected) > 0.03f) {
                throw new AssertionError("Sky linear radiance mismatch: " + e.x / FastMath.PI + " expected " + expected);
            }
            com.jme3.environment.gi.GiProbeVolume volume = new com.jme3.environment.gi.GiProbeVolume();
            volume.setCoverage(new Vector3f(4, 4, 4));
            volume.setProbeCounts(new Vector3f(2, 2, 2));
            rootNode.attachChild(volume);
            rootNode.updateGeometricState();
            com.jme3.environment.gi.GiSceneBaker.Settings quality = new com.jme3.environment.gi.GiSceneBaker.Settings();
            quality.separateSky = true;
            quality.captureSize = 8;
            quality.radianceSamples = 64;
            quality.visibilityResolution = 4;
            quality.visibilitySamples = 8;
            quality.classifyProbes = false;
            quality.captureShadows = false;
            data = com.jme3.environment.gi.GiSceneBaker.bake(renderManager, rootNode, volume,
                    quality, view -> { }, () -> false, count -> { });
            if (!data.hasSeparatedSky()) {
                throw new AssertionError("Missing separated sky data");
            }
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                data.saveCompressed(bytes);
                data = com.jme3.environment.gi.GiProbeData.loadCompressed(
                        new java.io.ByteArrayInputStream(bytes.toByteArray()));
            } catch (java.io.IOException exception) {
                throw new AssertionError(exception);
            }
        }
        if (frames == 68) {
            // 第四步：局部反射探针仅提供镜面反射，不重复添加天空漫反射。
            com.jme3.light.LightProbe reflection = new com.jme3.light.LightProbe();
            reflection.setPrefilteredMap(skyLighting.getLight().getPrefilteredEnvMap());
            reflection.setNbMipMaps(skyLighting.getLight().getNbMipMaps());
            reflection.setShCoeffs(skyLighting.getLight().getShCoeffs());
            reflection.getArea().setRadius(4);
            reflection.setDiffuseEnabled(false);
            reflection.setReady(true);
            rootNode.addLight(reflection);
            localReflection = reflection;
        }
        if (frames >= 36 && frames < 100 && frames % 4 == 0) {
            viewPort.setRenderPath(paths[((frames - 36) / 4) % 4]);
            selectMaterial(((frames - 36) / 16) % 2 == 1);
        }
        if (frames == 100) {
            rootNode.removeLight(localReflection);
            binding.close();
            binding = null;
            data = null;
        }
        if (frames >= 100 && frames % 4 == 0) {
            // 第五步：渲染线程调节天光，无需重新捕捉；最后恢复默认 1。
            int phase = (frames - 100) / 32;
            skyLighting.getLight().setIntensity(phase == 0 ? 0f : phase == 1 ? 0.5f : 1f);
            viewPort.setRenderPath(paths[((frames - 100) / 4) % 4]);
            selectMaterial(((frames - 100) / 16) % 2 == 1);
        }
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (frames % 4 == 3) {
            ByteBuffer data = BufferUtils.createByteBuffer(64 * 64 * 4);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), data, Image.Format.RGBA8);
            int index = (32 * 64 + 32) * 4;
            int red = data.get(index) & 255;
            int green = data.get(index + 1) & 255;
            if ((frames < 32 || frames >= 36 && frames < 100) && !(red > green && green > 0)) {
                throw new AssertionError("Missing HDR sky lighting at frame " + frames + ": " + red + "," + green);
            }
            if (frames >= 32 && frames < 36 && red != 0) {
                throw new AssertionError("Disabled sky still lights receiver: " + red);
            }
            if (frames >= 100) {
                int model = ((frames - 100) / 16) % 2;
                float intensity = skyLighting.getLight().getIntensity();
                if (Math.abs(red - baseline[model][0] * intensity) > 2
                        || Math.abs(green - baseline[model][1] * intensity) > 2) {
                    throw new AssertionError("Sky intensity mismatch at frame " + frames);
                }
            } else if (frames != 35) {
                int model = frames < 32 ? frames / 16 : ((frames - 36) / 16) % 2;
                if (baseline[model] == null) {
                    baseline[model] = new int[]{red, green};
                } else if (Math.abs(red - baseline[model][0]) > 2
                        || Math.abs(green - baseline[model][1]) > 2) {
                    throw new AssertionError("Lighting energy/path mismatch at frame " + frames);
                }
            }
            System.out.println("SKY_LIGHT_CHECK frame=" + frames + " rgb=" + red + "," + green);
        }
        frames++;
        if (frames == 196) {
            System.out.println("SKY_LIGHT_PASS png=" + png);
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
