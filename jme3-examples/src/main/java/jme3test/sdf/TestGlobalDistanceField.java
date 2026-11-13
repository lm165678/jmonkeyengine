package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.GlobalDistanceField;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.scene.plugins.sdf.SurfaceBakeData;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Loads BakeTank.j3o and displays three transformed instances or their global field.
 * OpenGL 3.2/GLSL150; CPU incremental composition, GPU ray-march visualization.
 * Run main or gradlew :jme3-examples:globalDistanceField.
 * Verification: gradlew :jme3-examples:globalDistanceFieldSmoke.
 * Space: models/global field; C: cascade colors; M: animate one rigid instance;
 * R: remove/restore that instance. Drag mouse + WASD/QZ moves the camera.
 * No baking or asset writes occur. Saved surface attributes stay on CPU and are
 * shared between clones; this example neither uploads them nor calculates GI.
 * The unsigned Tank source and low-resolution clipmaps show a thick approximate
 * surface envelope. This is not an exact mesh replacement or a DDGI ray tracer.
 * Raw source distances are preserved during composition. The half-cell hit
 * tolerance belongs only to this unsigned preview, not to stored geometry.
 * Smoke checks compare a white triangle silhouette against the field at the same
 * camera, reject excessive dilation and reject ray-march iteration exhaustion.
 *
 * <pre>{@code
 * // 第一步：加载已经烘焙的资产，不调用 SdfGenerator 或 SurfaceAttributeBaker。
 * Spatial asset = assetManager.loadModel("Models/Tank/BakeTank.j3o");
 * SdfVolume sdf = asset.getUserData(SdfVolume.USER_DATA_KEY);
 * SurfaceBakeData attributes = asset.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * // 第二步：创建实例；局部烘焙数据只读共享，世界变换各自独立。
 * Spatial instance = asset.clone(false);
 * instance.setUserData(SdfVolume.USER_DATA_KEY, sdf);
 * instance.setUserData(SurfaceBakeData.USER_DATA_KEY, attributes);
 * instance.setLocalTranslation(5, 0, 0);
 * instance.setLocalScale(1.2f, 0.8f, 1.5f);
 * instance.rotate(0, 0.6f, 0);
 * rootNode.attachChild(instance);
 * // 第三步：创建三级相机 clipmap，每级格距翻倍，不是每个模型都有三级 SDF。
 * GlobalDistanceField field = new GlobalDistanceField(128, 3, 0.3f);
 * // 第四步：每帧变换更新后执行。自动发现有 SDF 的节点，无 SDF 的模型正常渲染但不合并。
 * rootNode.updateGeometricState();
 * field.update(rootNode, cam.getLocation(), renderer);
 * // 第五步：j3md 中声明三维纹理；把 field.getTexture(i)/getMin(i)/getCellSize(i) 绑定到材质。
 * // 存储的是原始距离估计，不能将保守步进余量减进缓存，否则会改变表面轮廓。
 * // 本例的半格命中容差只用于无符号场预览；精确命中需要模型级细化，不能直接用于 DDGI。
 * // 空格只改变显示，隐藏原模型不注销实例。M/R 演示移动与移除的旧区域失效。
 * // 第六步：不变的帧 getUpdatedVoxelCount()==0；移动相机只重新计算新增格点。
 * // 第七步：先移除可视化消费者，再在渲染线程 field.close()，不销毁资产烘焙数据。
 * }</pre>
 */
public final class TestGlobalDistanceField extends SimpleApplication {
    private final Node models = new Node("SDF instances");
    private final Spatial[] tanks = new Spatial[3];
    private GlobalDistanceField global;
    private Material preview;
    private Geometry screen;
    private BitmapText label;
    private boolean showField;
    private boolean colors;
    private boolean animate;
    private boolean smoke;
    private int frame;
    private float time;
    private final Material[] originalMaterials = new Material[3];
    private boolean[] referenceMask;

    public static void main(String[] args) {
        TestGlobalDistanceField app = new TestGlobalDistanceField();
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setResolution(1100, 720);
        settings.setAudioRenderer(null);
        settings.setTitle("Three baked Tanks / camera clipmap distance field");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        Spatial asset = assetManager.loadModel("Models/Tank/BakeTank.j3o");
        SdfVolume sdf = asset.getUserData(SdfVolume.USER_DATA_KEY);
        SurfaceBakeData attributes = asset.getUserData(SurfaceBakeData.USER_DATA_KEY);
        if (sdf == null || attributes == null || !attributes.isCompatibleWith(sdf)) {
            throw new IllegalStateException("BakeTank must contain compatible SDF and surface data");
        }
        Vector3f extent = sdf.getMax().subtract(sdf.getMin());
        float scale = 7f / Math.max(extent.x, Math.max(extent.y, extent.z));
        rootNode.attachChild(models);
        for (int i = 0; i < tanks.length; i++) {
            Spatial tank = asset.clone(false);
            tank.setName("Baked Tank " + i);
            // 显式共享只读烘焙资产；不为每个克隆创建一套体纹理/属性纹理。
            tank.setUserData(SdfVolume.USER_DATA_KEY, sdf);
            tank.setUserData(SurfaceBakeData.USER_DATA_KEY, attributes);
            tank.setLocalScale(scale * (0.8f + i * 0.2f), scale * (1.2f - i * 0.2f), scale);
            tank.setLocalRotation(new Quaternion().fromAngles(i * 0.08f, i * 0.55f, i * 0.12f));
            tank.setLocalTranslation((i - 1) * 7, i * 0.5f, (i - 1) * 1.5f);
            models.attachChild(tank);
            tanks[i] = tank;
            originalMaterials[i] = ((Geometry) tank).getMaterial();
        }
        DirectionalLight light = new DirectionalLight();
        light.setDirection(new Vector3f(-0.4f, -0.8f, -0.3f).normalizeLocal());
        light.setColor(ColorRGBA.White.mult(3));
        rootNode.addLight(light);
        cam.setFrustumPerspective(65, (float) cam.getWidth() / cam.getHeight(), 0.05f, 200);
        cam.setLocation(new Vector3f(0, 10, 10));
        cam.lookAt(new Vector3f(0, 0, 0), Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(8);
        flyCam.setDragToRotate(true);
        global = new GlobalDistanceField(128, 3, 0.3f);
        preview = new Material(assetManager, "Materials/Sdf/Global.j3md");
        for (int i = 0; i < 3; i++) {
            preview.setTexture("Field" + i, global.getTexture(i));
        }
        preview.setVector3("Cells", new Vector3f(global.getCellSize(0), global.getCellSize(1), global.getCellSize(2)));
        preview.setFloat("Resolution", global.getResolution());
        preview.setBoolean("CascadeColors", false);
        screen = new Geometry("Global distance field fullscreen", new Quad(1, 1));
        screen.setMaterial(preview);
        guiNode.attachChild(screen);
        label = new BitmapText(guiFont);
        label.setLocalTranslation(10, cam.getHeight() - 10, 1);
        guiNode.attachChild(label);
        setDisplayStatView(false);
        inputManager.addMapping("View", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("Colors", new KeyTrigger(KeyInput.KEY_C));
        inputManager.addMapping("Motion", new KeyTrigger(KeyInput.KEY_M));
        inputManager.addMapping("Remove", new KeyTrigger(KeyInput.KEY_R));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("View")) {
                showField = !showField;
            } else if (name.equals("Colors")) {
                colors = !colors;
            } else if (name.equals("Motion")) {
                animate = !animate;
            } else if (tanks[2].getParent() == null) {
                models.attachChild(tanks[2]);
            } else {
                tanks[2].removeFromParent();
            }
        }, "View", "Colors", "Motion", "Remove");
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        time += tpf;
        if (animate) {
            tanks[2].setLocalTranslation(7, 1, 1.5f + (float) Math.sin(time) * 3);
            tanks[2].setLocalRotation(new Quaternion().fromAngles(0.16f, time * 0.4f, 0.24f));
        }
        if (smoke) {
            if (frame == 4) {
                // 精确三角形轮廓参考：统一白色，不受灯光/材质暗部影响。
                Material silhouette = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                silhouette.setColor("Color", ColorRGBA.White);
                models.setMaterial(silhouette);
            } else if (frame == 5) {
                for (int i = 0; i < tanks.length; i++) {
                    tanks[i].setMaterial(originalMaterials[i]);
                }
                showField = true;
            } else if (frame == 10) {
                tanks[2].move(2, 0, 0);
            } else if (frame == 12) {
                tanks[2].removeFromParent();
            } else if (frame == 14) {
                models.attachChild(tanks[2]);
            } else if (frame == 16) {
                cam.setLocation(cam.getLocation().add(0.7f, 0, 0));
                colors = true;
            }
        }
        // 独立于可见性扫描模型；更新世界变换后再合并，避免使用上一帧姿态。
        rootNode.updateGeometricState();
        global.update(models, cam.getLocation(), renderer);
        if (smoke && frame == 3 && global.getUpdatedVoxelCount() != 0) {
            throw new AssertionError("Static frame unexpectedly rebuilt clipmaps");
        }
        if (smoke && (frame == 10 || frame == 12 || frame == 14 || frame == 16)
                && global.getUpdatedVoxelCount() == 0) {
            throw new AssertionError("Scene/camera changes failed to invalidate clipmaps");
        }
        if (smoke && global.getInstanceCount() != (frame >= 12 && frame < 14 ? 2 : 3)) {
            throw new AssertionError("Scene registration/removal failed");
        }
        for (int i = 0; i < 3; i++) {
            preview.setVector3("Min" + i, global.getMin(i));
        }
        preview.setMatrix4("ClipToWorld", cam.getViewProjectionMatrix().invert());
        preview.setBoolean("CascadeColors", colors);
        models.setCullHint(showField ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        screen.setCullHint(showField ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        label.setText((showField ? "Global distance field" : "Original baked models")
                + " | instances=" + global.getInstanceCount() + " | CPU resampled=" + global.getUpdatedVoxelCount()
                + "\nSpace: view | C: cascade colors | M: motion | R: remove/restore | Drag + WASD/QZ"
                + "\n3 x " + global.getResolution() + "^3 R32F = " + global.getTextureBytes() / (1024 * 1024f)
                + " MiB | surface attribute textures not uploaded | approximate unsigned envelope");
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (smoke && (frame == 4 || frame == 8 || frame == 18)) {
            savePreview();
        }
        if (smoke && frame == 22) {
            System.out.println("GLOBAL_DISTANCE_FIELD_PASS instances=" + global.getInstanceCount());
            stop();
        }
    }

    private void savePreview() {
        int width = cam.getWidth();
        int height = cam.getHeight();
        ByteBuffer bytes = BufferUtils.createByteBuffer(width * height * 4);
        renderer.readFrameBufferWithFormat(null, bytes, Image.Format.RGBA8);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int pixels = 0;
        int exhausted = 0;
        int referencePixels = 0;
        int outsideReference = 0;
        if (frame == 4) {
            referenceMask = new boolean[width * height];
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (x + width * y) * 4;
                int r = bytes.get(offset) & 255;
                int g = bytes.get(offset + 1) & 255;
                int b = bytes.get(offset + 2) & 255;
                image.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                if (y > 30 && y < height - 100) {
                    if (frame == 4) {
                        referenceMask[x + width * y] = r > 200 && g > 200 && b > 200;
                    }
                    if (frame != 4 && r > 150 && g < 20 && b > 100) {
                        exhausted++;
                    }
                    if (b > 30 && g > 20) {
                        pixels++;
                        if (frame == 8 && !nearReference(x, y, width, height)) {
                            outsideReference++;
                        }
                    }
                    if (frame == 8 && referenceMask[x + width * y]) {
                        referencePixels++;
                    }
                }
            }
        }
        if (pixels < 1000) {
            throw new AssertionError("Missing global field surfaces: " + pixels);
        }
        if (exhausted != 0) {
            throw new AssertionError("Ray-march iteration exhaustion pixels: " + exhausted);
        }
        if (frame == 8) {
            float ratio = (float) pixels / referencePixels;
            float outside = (float) outsideReference / pixels;
            System.out.println("GLOBAL_FIELD_SILHOUETTE areaRatio=" + ratio + " outside8px=" + outside);
            if (ratio < 0.65f || ratio > 1.45f || outside > 0.2f) {
                throw new AssertionError("Global field silhouette diverges from original mesh");
            }
        }
        try {
            Path base = Paths.get("build", "sdf");
            Files.createDirectories(base);
            Path file = Files.createTempDirectory(base, "global-").resolve("preview.png");
            ImageIO.write(image, "png", file.toFile());
            System.out.println("GLOBAL_FIELD_PREVIEW " + file.toAbsolutePath());
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean nearReference(int x, int y, int width, int height) {
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && py >= 0 && px < width && py < height && referenceMask[px + width * py]) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (smoke) {
            error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }

    @Override
    public void destroy() {
        if (global != null) {
            guiNode.detachChild(screen);
            global.close();
        }
        super.destroy();
    }
}
