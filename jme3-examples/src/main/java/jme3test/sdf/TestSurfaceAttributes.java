package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.*;
import com.jme3.scene.plugins.sdf.*;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Tank model and its material surface cards, OpenGL 3.3, no DDGI.
 * Run main / gradlew :jme3-examples:surfaceAttributes; append Smoke for GPU checks.
 * Asset: Models/Tank/tank.j3o and Models/Tank/tank.j3m; never overwritten.
 * Default PBR uses original Tank textures. 1/2/3 compare Lighting/PBR/Unshaded;
 * F cycles six capture directions, C changes tint, R explicitly recaptures.
 * N toggles normal map, E toggles PBR emission map. Drag mouse + WASD/QZ moves camera.
 * Bottom cards: diffuse reflectance, geometry-local normal, emission.
 * Small validation panels: green is a captured surface reprojected successfully;
 * red is a point behind that surface rejected by captured depth; black is no coverage.
 * Six directions do not guarantee complete coverage of the Tank's concave details.
 *
 * <pre>{@code
 * // 第一步：使用真实 Tank 模型与原生 PBR 材质，shader 由 j3md 声明。
 * Geometry model = (Geometry) assetManager.loadModel("Models/Tank/tank.j3o");
 * model.setMaterial(assetManager.loadMaterial("Models/Tank/tank.j3m"));
 * MikktspaceTangentGenerator.generate(model);
 * // 第二步：从 j3o 取回表面属性；缺少数据时在渲染线程的视口边界一次烘焙所有方向。
 * // 该调用同步完成布局、映射和属性捕获，不生成 SDF；不能放在正在绘制的 Pass 中。
 * SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * if (data == null) {
 *     data = SurfaceAttributeBaker.bake(model, dependencyRevision, renderManager, viewPort, 128, null);
 *     data.attachTo(model);
 * }
 * // 第三步：另存 j3o 后，布局和实际属性像素都会保存；重载无需再次捕获。
 * // 生产环境先用 data.validate(model, dependencyRevision) 检查几何和材质依赖。
 * SurfaceAttributeData.Textures textures = data.getAttributes().uploadAll();
 * // 第四步：切换方向只选取已有纹理，不重新 bake；4 是 +Z 卡片。
 * Texture2D diffuse = textures.getTexture(4, SurfaceAttributeCache.Channel.DIFFUSE);
 * Texture2D depth = textures.getDepthTexture(4);
 * // 第五步：用布局投影命中点，并校验 depth/coverage 后再读取属性，不能忽略遮挡。
 * // 第六步：几何或材质改变时重新 bake；纯光源变化无需重烘焙这些无光照属性。
 * // 第七步：移除预览消费者后，在渲染线程 textures.close() 释放纹理。
 * // 所有属性同时上传适合本例；大世界的分卡片驻留管理属于后续扩展。
 * }</pre>
 */
public class TestSurfaceAttributes extends SimpleApplication {
    protected boolean lifecycle;
    protected boolean materialMaps;
    private boolean smoke;
    private int frame;
    private int mode = 1;
    private int face = 4;
    private boolean tinted;
    private boolean normalMap = true;
    private boolean emissionMap = true;
    private boolean dirty = true;
    protected Geometry source;
    private Material tankMaterial;
    private SurfaceAttributeCache cache;
    private SurfaceBakeData baked;
    private SurfaceAttributeData.Textures uploaded;
    private boolean rebake = true;
    private int bakeCount;
    private final Node previews = new Node("Tank surface cards");
    private BitmapText help;

    public static void main(String[] args) {
        launch(new TestSurfaceAttributes(), args);
    }

    protected static void launch(TestSurfaceAttributes app, String[] args) {
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setResolution(1100, 800);
        settings.setAudioRenderer(null);
        settings.setTitle("Tank surface attribute cache");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        setDisplayStatView(false);
        setDisplayFps(false);
        source = (Geometry) assetManager.loadModel("Models/Tank/tank.j3o");
        // 原始测试资产的材质在单独的 j3m；保持其贴图参数并补齐切线。
        tankMaterial = assetManager.loadMaterial("Models/Tank/tank.j3m");
        com.jme3.util.mikktspace.MikktspaceTangentGenerator.generate(source);
        setMaterial();
        rootNode.attachChild(source);
        rootNode.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal(), ColorRGBA.White));
        rootNode.updateGeometricState();
        com.jme3.bounding.BoundingBox bounds = (com.jme3.bounding.BoundingBox) source.getWorldBound();
        Vector3f center = bounds.getCenter().clone();
        float radius = bounds.getExtent(new Vector3f()).length() * 2;
        // 上方视口显示模型，下方 GUI 显示缓存，避免卡片遮住 Tank。
        cam.setViewPort(0, 1, .36f, 1);
        cam.setFrustumPerspective(45, cam.getWidth() / (cam.getHeight() * .64f), radius / 1000, radius * 10);
        cam.setLocation(center.add(new Vector3f(.6f, .4f, .7f).multLocal(radius)));
        cam.lookAt(center, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(radius * .3f);
        flyCam.setDragToRotate(true);
        guiNode.attachChild(previews);
        help = new BitmapText(guiFont);
        help.setLocalTranslation(12, 790, 1);
        guiNode.attachChild(help);
        String[] names = {"Lighting", "PBR", "Unshaded", "Tint", "Recapture", "Face", "Normal", "Emission"};
        int[] keys = {KeyInput.KEY_1, KeyInput.KEY_2, KeyInput.KEY_3, KeyInput.KEY_C, KeyInput.KEY_R,
                KeyInput.KEY_F, KeyInput.KEY_N, KeyInput.KEY_E};
        for (int i = 0; i < names.length; i++) {
            inputManager.addMapping(names[i], new KeyTrigger(keys[i]));
        }
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("Tint")) {
                tinted = !tinted;
            } else if (name.equals("Face")) {
                face = (face + 1) % 6;
            } else if (name.equals("Normal")) {
                normalMap = !normalMap;
            } else if (name.equals("Emission")) {
                emissionMap = !emissionMap;
            } else if (!name.equals("Recapture")) {
                mode = name.equals("Lighting") ? 0 : name.equals("PBR") ? 1 : 2;
            }
            setMaterial();
            rebake = !name.equals("Face");
            dirty = true;
        }, names);
    }

    private void setMaterial() {
        ColorRGBA tint = tinted ? new ColorRGBA(.2f, 1, .2f, 1) : ColorRGBA.White;
        Material material;
        if (mode == 1) {
            material = tankMaterial.clone();
            material.setColor("BaseColor", tint);
            if (!normalMap) {
                material.clearParam("NormalMap");
            }
            if (!emissionMap) {
                material.clearParam("EmissiveMap");
            }
        } else {
            material = new Material(assetManager, mode == 0
                    ? "Common/MatDefs/Light/Lighting.j3md" : "Common/MatDefs/Misc/Unshaded.j3md");
            material.setTexture(mode == 0 ? "DiffuseMap" : "ColorMap", tankMaterial.getTextureParam("BaseColorMap").getTextureValue());
            if (mode == 0) {
                material.setBoolean("UseMaterialColors", true);
                material.setColor("Diffuse", tint);
                if (normalMap) {
                    material.setTexture("NormalMap", tankMaterial.getTextureParam("NormalMap").getTextureValue());
                }
            } else {
                material.setColor("Color", tint);
            }
        }
        source.setMaterial(material);
    }

    private void show(com.jme3.texture.Texture2D[] colors, com.jme3.texture.Texture2D depth, Matrix4f localToClip) {
        previews.detachAllChildren();
        for (int i = 0; i < 3; i++) {
            Material preview = new Material(assetManager, "Materials/Sdf/CardPreview.j3md");
            preview.setTexture("Attribute", colors[i]);
            preview.setInt("Mode", i == 1 ? 1 : 0);
            panel(preview, 12 + i * 280, 12, 256);
        }
        for (int i = 0; i < 2; i++) {
            Material coverage = new Material(assetManager, "Materials/Sdf/CardPreview.j3md");
            coverage.setTexture("Depth", depth);
            coverage.setMatrix4("LocalToClip", localToClip);
            coverage.setMatrix4("ClipToLocal", localToClip.invert());
            coverage.setInt("Mode", i == 0 ? 3 : 4);
            panel(coverage, 860, 12 + i * 130, 120);
        }
        help.setText("Tank | 1 Lighting / 2 PBR / 3 Unshaded | C tint | F face | R recapture | N normal | E emission"
                + "\nMode=" + mode + " face=" + face + " bakes=" + bakeCount + " | saved attribute cards=" + baked.getAttributes().getCardCount()
                + "\nBottom: diffuse / local normal / emission | Right: valid surface (green), behind surface (red)");
    }

    private void panel(Material material, int x, int y, int size) {
        Geometry quad = new Geometry("Tank card preview", new Quad(size, size));
        quad.setMaterial(material);
        quad.setLocalTranslation(x, y, 0);
        previews.attachChild(quad);
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        if (smoke && (frame == 12 || frame == 24 || frame == 36 || frame == 48)) {
            mode = frame == 12 ? 0 : frame == 24 ? 2 : 1;
            if (frame == 36) {
                tinted = true;
                if (materialMaps) {
                    normalMap = false;
                    emissionMap = false;
                }
            }
            if (frame == 48) {
                face = 2;
            }
            setMaterial();
            rebake = frame != 48;
            dirty = true;
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (dirty) {
            previews.detachAllChildren();
            if (rebake || baked == null) {
                // 一次调用包含布局、全部方向属性和深度；不依赖 SDF。
                SurfaceBakeData completed = bakeSurfaceData();
                if (uploaded != null) {
                    uploaded.close();
                }
                if (cache != null) {
                    cache.close();
                }
                baked = completed;
                bakeCount++;
                uploaded = baked.getAttributes().uploadAll();
                if (lifecycle) {
                    cache = new SurfaceAttributeCache(source, baked, "tank-demo-v1", 128, 2);
                }
            }
            com.jme3.texture.Texture2D[] colors = new com.jme3.texture.Texture2D[3];
            com.jme3.texture.Texture2D depth;
            Matrix4f localToClip;
            if (lifecycle) {
                // 仅生命周期专项案例演示底层逐卡片捕获；普通案例使用一键 bake 的结果。
                SurfaceAttributeCache.Card card = cache.capture(face, manager, viewPort);
                long draws = cache.getCaptureCount();
                if (cache.capture(face, manager, viewPort) != card || draws != cache.getCaptureCount()) {
                    throw new AssertionError("Resident card not reused");
                }
                cache.capture((face + 1) % 6, manager, viewPort);
                cache.capture((face + 2) % 6, manager, viewPort);
                if (cache.getResident(face) != null || cache.getResidentCount() != 2) {
                    throw new AssertionError("LRU budget not enforced");
                }
                card = cache.capture(face, manager, viewPort);
                for (int i = 0; i < 3; i++) {
                    colors[i] = card.getTexture(SurfaceAttributeCache.Channel.values()[i]);
                }
                depth = card.getDepthTexture();
                localToClip = card.getLocalToClip();
            } else {
                for (int i = 0; i < 3; i++) {
                    colors[i] = uploaded.getTexture(face, SurfaceAttributeCache.Channel.values()[i]);
                }
                depth = uploaded.getDepthTexture(face);
                localToClip = baked.getLayout().createCamera(face, baked.getAttributes().getResolution()).getViewProjectionMatrix();
            }
            show(colors, depth, localToClip);
            rebake = false;
            dirty = false;
        }
        if (smoke && (frame == 8 || frame == 20 || frame == 32 || frame == 44 || frame == 56)) {
            verifyPixels();
        }
        if (smoke && frame == 60) {
            previews.detachAllChildren();
            if (cache != null) {
                cache.close();
            }
            uploaded.close();
            System.out.println(lifecycle ? "SURFACE_CACHE_LIFECYCLE_PASS Tank" : "SURFACE_ATTRIBUTES_PASS Tank");
            stop();
        }
    }

    /**
     * SDK button hook: independent one-call GPU attribute bake, no SDF.
     * Override in the persistence example to save/reload before displaying.
     * @return complete surface bake, generated at a renderer viewport boundary
     */
    protected SurfaceBakeData bakeSurfaceData() {
        return SurfaceAttributeBaker.bake(source, "tank-demo-v1", renderManager, viewPort, 128, null);
    }
    private void verifyPixels() {
        ByteBuffer pixels = BufferUtils.createByteBuffer(cam.getWidth() * cam.getHeight() * 4);
        renderer.readFrameBufferWithFormat(null, pixels, Image.Format.RGBA8);
        int frontValid = 0;
        int backRejected = 0;
        int normalPixels = 0;
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 120; x++) {
                int front = rgb(pixels, 860 + x, 12 + y);
                int back = rgb(pixels, 860 + x, 142 + y);
                if (front == 0x00ff00) {
                    frontValid++;
                } else if (front != 0) {
                    throw new AssertionError("Captured surface failed depth reprojection");
                }
                if (back == 0xff0000) {
                    backRejected++;
                } else if (back != 0) {
                    throw new AssertionError("Occluded surface incorrectly accepted");
                }
            }
        }
        for (int y = 12; y < 268; y++) {
            for (int x = 292; x < 548; x++) {
                if (rgb(pixels, x, y) > 0x202020) {
                    normalPixels++;
                }
            }
        }
        if (frontValid < 100 || backRejected < 100 || normalPixels < 100) {
            throw new AssertionError("Tank card empty: " + frontValid + "/" + backRejected + "/" + normalPixels);
        }
        if (frame == 8) {
            savePreview(pixels);
        }
    }

    private void savePreview(ByteBuffer pixels) {
        try {
            java.nio.file.Path base = java.nio.file.Paths.get("build", "sdf");
            java.nio.file.Files.createDirectories(base);
            java.nio.file.Path output = java.nio.file.Files.createTempDirectory(base, "tank-cache-").resolve("preview.png");
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(cam.getWidth(), cam.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < cam.getHeight(); y++) {
                for (int x = 0; x < cam.getWidth(); x++) {
                    image.setRGB(x, cam.getHeight() - y - 1, rgb(pixels, x, y));
                }
            }
            javax.imageio.ImageIO.write(image, "png", output.toFile());
            System.out.println("TANK_CACHE_PREVIEW " + output.toAbsolutePath());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int rgb(ByteBuffer pixels, int x, int y) {
        int index = (x + y * cam.getWidth()) * 4;
        return ((pixels.get(index) & 255) << 16) | ((pixels.get(index + 1) & 255) << 8) | (pixels.get(index + 2) & 255);
    }

    @Override
    public void handleError(String message, Throwable throwable) {
        if (smoke) {
            System.err.println(message);
            throwable.printStackTrace();
            stop();
            System.exit(1);
        } else {
            super.handleError(message, throwable);
        }
    }

    @Override
    public void destroy() {
        if (cache != null) {
            cache.close();
        }
        if (uploaded != null) {
            uploaded.close();
        }
        super.destroy();
    }
}
