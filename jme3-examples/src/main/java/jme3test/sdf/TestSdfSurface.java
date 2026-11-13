package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.SdfGenerator;
import com.jme3.scene.plugins.sdf.SdfSettings;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.scene.shape.Box;
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
 * Full-volume GPU distance-field preview, switched against the original model.
 * Requires OpenGL 3.2 / GLSL 150 and a filterable R32F 3D texture, no compute.
 *
 * <p>运行：gradlew :jme3-examples:sdfSurface；或 IDEA 运行 main。
 * 默认 Sponza 96x48x96 UNSIGNED，初次生成会同步等待数秒。
 * -PsdfBox（IDEA: --box）切换为闭合方盒 48x48x48 EVEN_ODD。
 * 空格切换模型/SDF，H 切换 SDF 的法线明暗/步数热力图；鼠标拖动观察，WASD/QZ 移动。
 * 蓝到红代表步进由少到多；紫色代表达到 512 步上限，并非表面命中。
 * 冒烟验证：gradlew :jme3-examples:sdfSurfaceSmoke，可加 -PsdfBox。
 * 自动切换两种视图、读取 SDF 帧缓冲检查并另存截图，然后退出。</p>
 *
 * <p>这是距离场调试渲染，不是原模型换材质，也没有生成简化三角网格。
 * 仅绘制全屏 Quad；每像素从相机发射局部空间光线，求包围盒交点，采样 Texture3D，
 * 步进到近表面并用距离梯度估算法线。j3md 持有 shader 声明，应用不手动加载 frag。
 * Sponza 为开放建筑，UNSIGNED 没有可靠零交叉，使用半格对角线厚度带显示；
 * 薄墙会变厚、相邻结构可能粘连。闭合方盒使用接近零距离的阈值。
 * 三线性插值、命中阈值及步数上限使本算法仅为近似预览，不可直接当 DDGI 遮挡查询。
 * 该示例不与模型混合、不输出场景深度；正式管线合成需另行处理深度与遮挡。
 * 不保存模型，不覆盖 Sponza.j3o。已有 userdata 距离场会直接复用。</p>
 *
 * <pre>{@code
 * // 第一步：获取生成/加载的距离场，坐标属于 model 根节点局部空间。
 * SdfVolume field = model.getUserData(SdfVolume.USER_DATA_KEY);
 * // 若为空：SdfGenerator.generate(model, settings, progress)。
 * // 第二步：创建一次体纹理和普通材质；不要每帧重新上传体数据。
 * Material preview = new Material(assetManager, "Materials/Sdf/Surface.j3md");
 * preview.setTexture("DistanceField", field.toTexture3D());
 * preview.setVector3("BoundsMin", field.getMin());
 * preview.setVector3("BoundsMax", field.getMax());
 * preview.setVector3("Resolution", new Vector3f(field.getSizeX(), field.getSizeY(), field.getSizeZ()));
 * preview.setVector3("CellSize", field.getCellSize());
 * preview.setFloat("HitDistance", field.getInterpolationMargin());
 * preview.setBoolean("StepHeatmap", false);
 * // 第三步：每帧更新裁剪空间到模型局部空间的变换，光线起点为相机近平面。
 * Matrix4f worldToLocal = model.getLocalToWorldMatrix(new Matrix4f()).invertLocal();
 * preview.setMatrix4("ClipToLocal", worldToLocal.mult(cam.getViewProjectionMatrix().invert()));
 * // 第四步：模型视图与全屏 SDF 视图互斥显示，切换时保持同一个相机。
 * model.setCullHint(Spatial.CullHint.Always);
 * // 第五步：Surface.frag 将格点位置转换为 texel 中心后采样，避免半格偏移。
 * // SDK 交互烘焙应采用快照 + 后台任务；本例只在启动阶段同步生成一次。
 * }</pre>
 */
public final class TestSdfSurface extends SimpleApplication {
    private boolean box;
    private boolean smoke;
    private boolean showSdf = true;
    private boolean heatmap;
    private int frame;
    private Spatial model;
    private Geometry screen;
    private Material material;
    private BitmapText help;
    private SdfVolume sdf;

    public static void main(String[] args) {
        TestSdfSurface app = new TestSdfSurface();
        app.box = Arrays.asList(args).contains("--box");
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1100, 720);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setAudioRenderer(null);
        settings.setTitle("Model / SDF surface");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // 第一步：原模型仅用于比较与烘焙，SDF 显示期间不会绘制其三角形。
        if (box) {
            model = new Geometry("Closed box", new Box(1, 1, 1));
            Material original = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            original.setColor("Color", ColorRGBA.Gray);
            model.setMaterial(original);
        } else {
            model = assetManager.loadModel("Models/Sponza/Sponza.j3o");
        }
        rootNode.attachChild(model);
        rootNode.addLight(new AmbientLight(ColorRGBA.White));
        rootNode.updateGeometricState();
        sdf = model.getUserData(SdfVolume.USER_DATA_KEY);
        if (sdf == null) {
            sdf = SdfGenerator.generate(model, new SdfSettings(box ? 48 : 96, 48, box ? 48 : 96,
                    .05f, box ? SdfSettings.SignMode.EVEN_ODD : SdfSettings.SignMode.UNSIGNED), null);
        }

        // 第二步：只上传一次 R32F 三维纹理。SDF 数据不依赖原模型的材质贴图。
        material = new Material(assetManager, "Materials/Sdf/Surface.j3md");
        material.setTexture("DistanceField", sdf.toTexture3D());
        material.setVector3("BoundsMin", sdf.getMin());
        material.setVector3("BoundsMax", sdf.getMax());
        material.setVector3("Resolution", new Vector3f(sdf.getSizeX(), sdf.getSizeY(), sdf.getSizeZ()));
        material.setVector3("CellSize", sdf.getCellSize());
        material.setFloat("HitDistance", sdf.getSignMode() == SdfSettings.SignMode.UNSIGNED
                ? sdf.getInterpolationMargin() : 0);
        material.setBoolean("StepHeatmap", false);
        screen = new Geometry("SDF fullscreen ray march", new Quad(1, 1));
        screen.setMaterial(material);
        guiNode.attachChild(screen);

        // 第三步：沿同一视角比较，局部空间追踪不会受根节点平移、旋转影响。
        Vector3f center = model.localToWorld(sdf.getMin().add(sdf.getMax()).multLocal(.5f), null);
        // SDF bounds 是局部单位；相机取景与裁剪距离必须转换到世界单位。
        Vector3f scale = model.getWorldScale();
        float maximumScale = Math.max(Math.abs(scale.x), Math.max(Math.abs(scale.y), Math.abs(scale.z)));
        float radius = sdf.getMax().subtract(sdf.getMin()).length() * maximumScale;
        cam.setFrustumPerspective(45, (float) cam.getWidth() / cam.getHeight(), radius / 1000, radius * 10);
        cam.setLocation(center.add(new Vector3f(.65f, .5f, .65f).multLocal(radius)));
        cam.lookAt(center, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(radius * .3f);
        flyCam.setDragToRotate(true);
        setDisplayStatView(false);
        help = new BitmapText(guiFont);
        // GUI 在同一深度时使用插入顺序；文本提高 Z，确保绘制在全屏预览之后。
        help.setLocalTranslation(10, cam.getHeight() - 10, 1);
        guiNode.attachChild(help);
        inputManager.addMapping("SwitchView", new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("Heatmap", new KeyTrigger(KeyInput.KEY_H));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("SwitchView")) {
                showSdf = !showSdf;
            } else {
                heatmap = !heatmap;
            }
            updateMode();
        }, "SwitchView", "Heatmap");
        updateMode();
    }

    private void updateMode() {
        model.setCullHint(showSdf ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        screen.setCullHint(showSdf ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        material.setBoolean("StepHeatmap", heatmap);
        help.setText((showSdf ? "SDF surface" : "Original model") + " | " + sdf.getSignMode()
                + " | " + sdf.getSizeX() + "x" + sdf.getSizeY() + "x" + sdf.getSizeZ()
                + "\nSpace: model/SDF | H: step heatmap | Drag mouse + WASD/QZ: camera"
                + "\nApproximate preview; unsigned fields show a thick surface band.");
    }

    @Override
    public void simpleUpdate(float tpf) {
        // 第四步：即使全屏面片在 GUI 中，也使用场景相机而非 GUI 正交相机生成光线。
        Matrix4f worldToLocal = model.getLocalToWorldMatrix(new Matrix4f()).invertLocal();
        material.setMatrix4("ClipToLocal", worldToLocal.mult(cam.getViewProjectionMatrix().invert()));
        if (smoke) {
            frame++;
            if (frame == 10 || frame == 15) {
                showSdf = !showSdf;
                updateMode();
            }
            if (frame == 25) {
                heatmap = true;
                updateMode();
            }
        }
    }

    @Override
    public void simpleRender(RenderManager renderManager) {
        if (!smoke) {
            return;
        }
        if (frame == 20) {
            savePreview();
        }
        if (frame == 35) {
            System.out.println("SDF_SURFACE_PASS mode=" + sdf.getSignMode());
            stop();
        }
    }

    private void savePreview() {
        // 第五步（仅冒烟验证）：渲染结束后读回一次，检查中心区域确实产生表面像素。
        // 正常交互路径不读回 GPU；截图另存唯一目录，不改写任何模型资产。
        int width = cam.getWidth();
        int height = cam.getHeight();
        ByteBuffer data = BufferUtils.createByteBuffer(width * height * 4);
        renderer.readFrameBufferWithFormat(null, data, Image.Format.RGBA8);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int surfacePixels = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (x + y * width) * 4;
                int r = data.get(offset) & 255;
                int g = data.get(offset + 1) & 255;
                int b = data.get(offset + 2) & 255;
                image.setRGB(x, height - 1 - y, (r << 16) | (g << 8) | b);
                if (x > width / 4 && x < width * 3 / 4 && y > height / 4 && y < height * 3 / 4
                        && g > 50 && b >= r) {
                    surfacePixels++;
                }
            }
        }
        if (surfacePixels < 500) {
            throw new AssertionError("Missing SDF surface pixels: " + surfacePixels);
        }
        try {
            Path base = Paths.get("build", "sdf");
            Files.createDirectories(base);
            Path output = Files.createTempDirectory(base, "surface-").resolve("preview.png");
            ImageIO.write(image, "png", output.toFile());
            System.out.println("SDF surface screenshot: " + output.toAbsolutePath());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not save preview", exception);
        }
    }
}
