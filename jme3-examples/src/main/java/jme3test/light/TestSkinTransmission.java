package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Thin-surface transmission, independent of the curvature LUT. Run
 * {@code gradlew :jme3-examples:skinTransmissionDemo}; T toggles transmission,
 * B toggles an external shadow caster. GLSL330/ES300, half-float filtering and
 * managed shadows are required; the desktop demo requests OpenGL4.3.
 *
 * <pre>{@code
 * // 第一步：原有预积分皮肤继续负责表面散射；厚度透射默认关闭。
 * Material skin = new Material(assetManager, SkinLutGenerator.MATERIAL);
 * // 第二步：绑定线性厚度图，R * SkinThickness 表示法线方向的毫米厚度。
 * // 不提供贴图时，整件物体使用 SkinThickness 的常量厚度。
 * // SDK 厚度图 A 保存有效出口覆盖率；烘焙不到出口的区域不透射。旧图需重新烘焙。
 * skin.setFloat("SkinThickness", 30f);
 * skin.setTexture("SkinThicknessMap", thicknessMap);
 * skin.setVector3("SkinAttenuationDistance", new Vector3f(3f, 1f, .5f));
 * skin.setFloat("SkinWorldToMillimeters", 1000f);
 * // 第三步：在渲染线程开启；修改 RGB 衰减距离和颜色也可近似硅胶、蜡。
 * skin.setBoolean("SkinTransmission", true);
 * // skin.setBoolean("SkinTransmission", false); // 恢复原曲率 LUT 路径。
 * }</pre>
 *
 * The map is normal thickness, not exact thickness for every light direction.
 * Alpha stores resolved exit coverage. Custom opaque-alpha maps preserve their
 * original behavior; the SDK's version 2 exports mark unresolved regions opaque.
 * Grazing paths clamp the cosine at .2. Managed shadows are evaluated at the
 * estimated light entry point; this preserves external blockers approximately,
 * but inaccurate/open thickness or nearby unrelated surfaces can still fail.
 * This is an additive thin-surface approximation, not a full energy-conserving
 * BSSRDF or multi-layer volume solver. It only changes this skin material.
 * Disabled variants omit thickness reads and additional shadow queries.
 * Forward fallback also works in deferred viewports. Bind/change materials on
 * their owning render thread; surface baking uses an isolated model snapshot.
 *
 * <p>Verification: {@code gradlew :jme3-examples:skinTransmissionSmoke} checks
 * disabled restoration, thin/thick attenuation, constant/map equivalence,
 * external blockers and deferred fallback using native OpenGL readback.
 */
public final class TestSkinTransmission extends SimpleApplication {
    private final boolean verify;
    private Material skin;
    private Geometry blocker;
    private int frame;
    private ColorRGBA thin;

    private TestSkinTransmission(boolean verify) {
        this.verify = verify;
    }

    public static void main(String[] args) {
        boolean verify = args.length > 0 && "--verify".equals(args[0]);
        var app = new TestSkinTransmission(verify);
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(256, 192);
        settings.setAudioRenderer(null);
        settings.setGpuSceneEnabled(false);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(verify ? JmeContext.Type.OffscreenSurface : JmeContext.Type.Display);
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(2);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        flyCam.setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        cam.setLocation(new Vector3f(0, 0, .25f));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        cam.setParallelProjection(true);
        cam.setFrustum(.001f, 1f, -.08f, .08f, .06f, -.06f);
        skin = new Material(assetManager, SkinLutGenerator.MATERIAL);
        skin.setFloat("SkinThickness", 2f);
        skin.setBoolean("EnvironmentLighting", false);
        skin.setBoolean("SkinTransmission", !verify);
        Geometry subject = new Geometry("2mm slab", new Box(.04f, .05f, .001f));
        subject.setMaterial(skin);
        subject.setShadowMode(ShadowMode.CastAndReceive);
        rootNode.attachChild(subject);
        blocker = new Geometry("External blocker", new Box(.055f, .055f, .005f));
        blocker.setLocalTranslation(0, 0, -.03f);
        blocker.setMaterial(new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md"));
        blocker.setShadowMode(ShadowMode.Cast);
        DirectionalLight sun = new DirectionalLight(Vector3f.UNIT_Z, ColorRGBA.White);
        sun.getShadowSettings().setMapSize(512);
        sun.getShadowSettings().setCascadeCount(1);
        sun.getShadowSettings().setShadowDistance(1f);
        sun.getShadowSettings().setNormalBias(.00002f);
        sun.getShadowSettings().setDepthBias(.00002f);
        sun.getShadowSettings().setFilterRadius(0);
        rootNode.addLight(sun);
        renderManager.getShadowManager(viewPort).setEnabled(true);
        FrameBuffer target = new FrameBuffer(256, 192, 1);
        target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA16F));
        target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        if (verify) {
            viewPort.setOutputFrameBuffer(target);
        } else {
            inputManager.addMapping("Transmission", new KeyTrigger(KeyInput.KEY_T));
            inputManager.addMapping("Blocker", new KeyTrigger(KeyInput.KEY_B));
            inputManager.addListener((com.jme3.input.controls.ActionListener) (name, down, tpf) -> {
                if (!down) {
                    return;
                }
                if ("Transmission".equals(name)) {
                    skin.setBoolean("SkinTransmission", !Boolean.TRUE.equals(skin.getParamValue("SkinTransmission")));
                } else if (blocker.getParent() == null) {
                    rootNode.attachChild(blocker);
                } else {
                    blocker.removeFromParent();
                }
            }, "Transmission", "Blocker");
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!verify || frame % 8 != 0) {
            return;
        }
        int phase = frame / 8;
        skin.setBoolean("SkinTransmission", phase != 0 && phase != 5);
        skin.setFloat("SkinThickness", phase == 2 || phase == 3 ? 20f : 2f);
        if (phase == 3) {
            Image image = new Image(Image.Format.RGBA32F, 1, 1, BufferUtils.createByteBuffer(16), ColorSpace.Linear);
            ImageRaster.create(image).setPixel(0, 0, new ColorRGBA(.1f, .1f, .1f, 1));
            skin.setTexture("SkinThicknessMap", new Texture2D(image));
        } else {
            skin.clearParam("SkinThicknessMap");
        }
        if (phase == 4) {
            rootNode.attachChild(blocker);
        } else {
            blocker.removeFromParent();
        }
        viewPort.setRenderPath(phase == 6 ? RenderPath.TiledDeferredGPU : RenderPath.Forward);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!verify) {
            return;
        }
        if (frame % 8 == 7) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(256 * 192 * 16);
            renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA32F);
            var values = pixels.asFloatBuffer();
            int pixel = (96 * 256 + 128) * 4;
            ColorRGBA color = new ColorRGBA(values.get(pixel), values.get(pixel + 1), values.get(pixel + 2), 1);
            int phase = frame / 8;
            System.out.println("TRANSMISSION " + phase + " " + color);
            if (phase == 1) {
                thin = color;
                check(color.r > .05f && color.r > color.g * 2f && color.g > color.b, "Thin RGB attenuation");
            } else if (phase == 3 || phase == 6) {
                check(Math.abs(color.r - thin.r) < .003f, "Map/constant or deferred mismatch");
            } else {
                check(color.r < .005f, "Disabled/thick/external blocker not dark");
            }
            if (phase == 6) {
                System.out.println("SKIN_TRANSMISSION_PASS");
                stop();
                System.exit(0);
            }
        }
        frame++;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
