package jme3test.light;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.RenderManager;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Offscreen regression for the actual managed PCF shader, not a CPU reimplementation.
 * Entry: main, no controls. Requires GLSL150 and ordinary RGBA8 textures.
 * Run {@code gradlew :jme3-examples:managedShadowFilterSmoke}.
 * Checks hard/soft edge continuity, monotonic visibility and atlas tile isolation.
 * Add --software or --hardware to test the actual legacy Shadows.glsllib modes
 * (Nearest, Bilinear, PCF4, PCF8 and Poisson) with both sampler types.
 * Production usage: Light.getShadowSettings().setFilterRadius(0/1/2); default 1.
 * Run {@code gradlew :jme3-examples:managedShadowFilterSmoke --args=--plane}
 * to verify receiver-plane correction on positive/negative slopes
 * with both soft radii and an almost-zero receiver bias. Managed shadows enable
 * this automatically; radius 0 and legacy processors keep their previous policy.
 * No bake, lifecycle changes or new texture fetches are required. The correction
 * uses the supplied receiver normal; highly perturbed normal maps are an
 * approximation, and degenerate projections fall back to ordinary biased PCF.
 * This low-level test material is only a filter fixture, not a scene material.
 */
public final class TestManagedShadowFiltering extends SimpleApplication {
    private static boolean legacy;
    private static boolean hardware;
    private static boolean plane;
    private Material material;
    private int frame;

    public static void main(String[] args) {
        plane = args.length > 0 && args[0].equals("--plane");
        legacy = args.length > 0 && !plane;
        hardware = legacy && args[0].equals("--hardware");
        TestManagedShadowFiltering app = new TestManagedShadowFiltering();
        AppSettings settings = new AppSettings(true);
        settings.setResolution(256, 32);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        // 第一步：创建含有深度阶跃的测试图集，最近邻读取原始深度。
        ByteBuffer data = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                data.putFloat(x < 8 ? 0.25f : 0.75f);
            }
        }
        data.flip();
        Texture2D atlas = new Texture2D(new Image(Image.Format.Depth32F, 16, 16, data, ColorSpace.Linear));
        atlas.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        atlas.setMagFilter(Texture.MagFilter.Nearest);
        if (legacy) {
            atlas.setShadowCompareMode(hardware ? Texture.ShadowCompareMode.LessOrEqual
                    : Texture.ShadowCompareMode.Off);
            material = new Material(assetManager, "MatDefs/LegacyShadowFilterCheck.j3md");
            material.setTexture("ShadowMap0", atlas);
            material.setBoolean("Hardware", hardware);
            material.setInt("Mode", 10);
        } else {
            material = new Material(assetManager, "MatDefs/ShadowFilterCheck.j3md");
            material.setTexture("Atlas", atlas);
            material.setVector2("AtlasSize", new Vector2f(16, 16));
            material.setVector4("Rect", new Vector4f(0, 0, 1, 1));
            material.setFloat("Radius", 0);
        }
        Picture picture = new Picture("Filtered depth comparison");
        picture.setMaterial(material);
        picture.setWidth(256);
        picture.setHeight(32);
        guiNode.attachChild(picture);
        setDisplayFps(false);
        setDisplayStatView(false);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (plane) {
            // 第一步：构造正负斜率平面，深度必须与纹素中心严格对应。
            Vector2f slope = frame < 4 ? new Vector2f(0.064f, 0.032f) : new Vector2f(-0.064f, -0.032f);
            ByteBuffer depths = BufferUtils.createByteBuffer(16 * 16 * 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    depths.putFloat(0.45f + slope.x * (x + 0.5f) / 16 + slope.y * (y + 0.5f) / 16);
                }
            }
            depths.flip();
            material.getTextureParam("Atlas").getTextureValue().getImage().setData(depths);
            material.setVector2("PlaneSlope", slope);
            // 第二步：保持采样数不变，用接收面梯度修正各采样点比较深度。
            material.setFloat("Radius", 1 + frame / 2 % 2);
            return;
        }
        // 第二步：逐档对照 0/1/2；最后限制到左半图集，邻块不得漏光。
        if (legacy) {
            Texture atlas = material.getTextureParam("ShadowMap0").getTextureValue();
            atlas.setMagFilter(hardware && frame >= 2 ? Texture.MagFilter.Bilinear : Texture.MagFilter.Nearest);
            atlas.setMinFilter(hardware && frame >= 2 ? Texture.MinFilter.BilinearNoMipMaps : Texture.MinFilter.NearestNoMipMaps);
            material.setInt("Mode", new int[]{10, 1, 3, 5, 4}[Math.min(frame / 2, 4)]);
        } else {
            material.setFloat("Radius", Math.min(frame / 2, 2));
            material.setVector4("Rect", new Vector4f(0, 0, frame >= 6 ? 0.5f : 1, 1));
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (frame % 2 == 1) {
            ByteBuffer pixels = BufferUtils.createByteBuffer(256 * 32 * 4);
            renderer.readFrameBufferWithFormat(null, pixels, Image.Format.RGBA8);
            if (plane && (pixels.get((16 * 256 + 128) * 4 + 1) & 255) > 240) {
                throw new AssertionError("Control must reproduce uncorrected slope self-shadow");
            }
            int previous = 0;
            int maxStep = 0;
            Set<Integer> levels = new HashSet<>();
            for (int x = 0; x < 256; x++) {
                int value = pixels.get((16 * 256 + x) * 4) & 255;
                if (plane && value != 255) {
                    throw new AssertionError("Receiver plane self-shadow at " + x + ": " + value);
                }
                if (value < previous || (!plane && !legacy && frame >= 6 && value != 0)) {
                    throw new AssertionError("Nonmonotonic edge or atlas light leak at " + x);
                }
                maxStep = Math.max(maxStep, value - previous);
                previous = value;
                levels.add(value);
            }
            if ((legacy || frame < 6) && previous != 255) {
                throw new AssertionError("Lit area was darkened");
            }
            if (!plane && frame >= 2 && (legacy || frame < 6) && (maxStep > 17 || levels.size() < (frame == 3 && legacy ? 16 : 32))) {
                throw new AssertionError("PCF still has discrete texel steps");
            }
            System.out.println("PCF phase=" + frame / 2 + " levels=" + levels.size() + " maxStep=" + maxStep);
            if (frame == (legacy ? 9 : 7)) {
                // 第三步：测试退出，所有资源随应用关闭；无需 SDK 或资产修改。
                System.out.println("SHARED SHADOW FILTER PASS legacy=" + legacy + " hardware=" + hardware + " plane=" + plane);
                stop();
            }
        }
        frame++;
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
