package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.CameraInput;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.plugins.sdf.SdfGenerator;
import com.jme3.scene.plugins.sdf.SdfSettings;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Interactive SDF slice and near-surface sample viewer; ordinary desktop renderer,
 * no compute shaders or DDGI required. Does not save or overwrite any model.
 *
 * <p>入口：直接运行 main，或 gradlew :jme3-examples:sdfVisualization。
 * 默认 Sponza，生成 64x32x64 无符号距离；加 -PsdfBox 显示闭合方盒的有符号距离。
 * IDEA 的程序参数对应 --box。启动时同步烘焙一次，Sponza 需要数秒；
 * 此处为便于阅读的同步示例，编辑器应使用 SdfMeshSnapshot + 后台生成方式。
 * X/Y/Z 切换切片轴，方向键左右移动切片，M 显隐模型，P 显隐采样点，
 * 空格显隐场景中的切片。鼠标按住拖动观察，WASD/RF 移动。
 * 右下角始终显示同一张切片：白色近表面、蓝色正距离、红色负距离。
 * 切片横纵方向：X 切片为 Y/Z，Y 切片为 X/Z，Z 切片为 X/Y。
 * 点云只显示 abs(distance) 不超过半个网格对角线的采样点，
 * 是离散距离带，不是精确零等值面或 DDGI 光线命中结果。</p>
 *
 * <pre>{@code
 * // 第一步：加载模型。SDF 数据始终使用该模型根节点的局部坐标。
 * Spatial model = assetManager.loadModel("Models/Sponza/Sponza.j3o");
 * // 第二步：若 j3o 已附带结果，直接复用；没有才生成。
 * SdfVolume sdf = model.getUserData(SdfVolume.USER_DATA_KEY);
 * if (sdf == null) {
 *     sdf = SdfGenerator.generate(model,
 *             new SdfSettings(64, 32, 64, .05f, SdfSettings.SignMode.UNSIGNED), null);
 * }
 * // 第三步：按格点读取切片；单位不是 0~1，而是模型局部长度单位。
 * float distance = sdf.getDistance(12, sdf.getSizeY() / 2, 12);
 * // 第四步：局部采样点转换到世界空间，与原始模型重合。
 * Vector3f local = sdf.getMin().add(sdf.getCellSize().mult(new Vector3f(12, 16, 12)));
 * Vector3f world = model.localToWorld(local, new Vector3f());
 * // 第五步：颜色映射只是调试展示，不修改距离，也不用于保守步进。
 * // 完整体纹理供后续 GPU 使用：Texture3D texture = sdf.toTexture3D();
 * // 当前示例只更新二维切片，切换时复用纹理，不逐帧分配体纹理。
 * }</pre>
 * Smoke: gradlew :jme3-examples:sdfVisualizationSmoke (real window, then exits).
 */
public final class TestSdfVisualization extends SimpleApplication {
    private boolean box;
    private boolean smoke;
    private int frame;
    private int axis = 1;
    private int slice;
    private SdfVolume sdf;
    private Spatial model;
    private Geometry plane;
    private Geometry points;
    private Texture2D sliceTexture;
    private BitmapText help;

    public static void main(String[] args) {
        TestSdfVisualization app = new TestSdfVisualization();
        app.box = Arrays.asList(args).contains("--box");
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1280, 800);
        settings.setTitle("SDF visualization");
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // 第一步：读取原模型；这个案例不写入任何 j3o。
        if (box) {
            model = new Geometry("Closed box", new Box(1, 1, 1));
            Material material = unshaded();
            material.setColor("Color", new ColorRGBA(.5f, .6f, .7f, 1));
            model.setMaterial(material);
        } else {
            model = assetManager.loadModel("Models/Sponza/Sponza.j3o");
        }
        rootNode.attachChild(model);
        rootNode.addLight(new AmbientLight(ColorRGBA.White));
        rootNode.updateGeometricState();

        // 第二步：复用附带数据，否则一次性生成；开放建筑使用 UNSIGNED。
        sdf = model.getUserData(SdfVolume.USER_DATA_KEY);
        if (sdf == null) {
            sdf = SdfGenerator.generate(model, new SdfSettings(64, 32, 64, .05f,
                    box ? SdfSettings.SignMode.EVEN_ODD : SdfSettings.SignMode.UNSIGNED), null);
        }
        Vector3f center = model.localToWorld(sdf.getMin().add(sdf.getMax()).multLocal(.5f), null);
        // SDF bounds 是局部单位；相机取景与裁剪距离必须转换到世界单位。
        Vector3f scale = model.getWorldScale();
        float maximumScale = Math.max(Math.abs(scale.x), Math.max(Math.abs(scale.y), Math.abs(scale.z)));
        float radius = sdf.getMax().subtract(sdf.getMin()).length() * maximumScale;
        cam.setFrustumPerspective(45, (float) cam.getWidth() / cam.getHeight(), radius / 1000, radius * 10);
        cam.setLocation(center.add(new Vector3f(.7f, .6f, .7f).multLocal(radius)));
        cam.lookAt(center, Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(radius * .3f);
        flyCam.setDragToRotate(true);
        // Z 留给切片轴选择，将相机升降改为 R/F，避免一次按键触发两个动作。
        inputManager.deleteMapping(CameraInput.FLYCAM_RISE);
        inputManager.deleteMapping(CameraInput.FLYCAM_LOWER);
        inputManager.addMapping(CameraInput.FLYCAM_RISE, new KeyTrigger(KeyInput.KEY_R));
        inputManager.addMapping(CameraInput.FLYCAM_LOWER, new KeyTrigger(KeyInput.KEY_F));
        inputManager.addListener(flyCam, CameraInput.FLYCAM_RISE, CameraInput.FLYCAM_LOWER);

        // 第三步：创建可复用二维纹理，场景平面与 GUI 使用同一个纹理对象。
        sliceTexture = new Texture2D();
        sliceTexture.setMagFilter(Texture.MagFilter.Nearest);
        sliceTexture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        plane = new Geometry("SDF slice");
        Material sliceMaterial = unshaded();
        sliceMaterial.setTexture("ColorMap", sliceTexture);
        sliceMaterial.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        plane.setMaterial(sliceMaterial);
        rootNode.attachChild(plane);
        slice = size(axis) / 2;
        updateSlice();
        Picture preview = new Picture("Distance slice preview");
        preview.setTexture(assetManager, sliceTexture, false);
        preview.setWidth(300);
        preview.setHeight(300);
        preview.setPosition(cam.getWidth() - 310, 10);
        guiNode.attachChild(preview);

        // 第四步：单个 Points Mesh 展示近表面格点，避免每个格点一个 Geometry。
        createPoints();
        help = new BitmapText(guiFont);
        help.setLocalTranslation(10, cam.getHeight() - 10, 0);
        guiNode.attachChild(help);
        setDisplayStatView(false);
        updateHelp();

        // 第五步：只在按键改变切片时更新 CPU 纹理与平面，不逐帧重新生成 SDF。
        bind("X", KeyInput.KEY_X);
        bind("Y", KeyInput.KEY_Y);
        bind("Z", KeyInput.KEY_Z);
        bind("Previous", KeyInput.KEY_LEFT);
        bind("Next", KeyInput.KEY_RIGHT);
        bind("Model", KeyInput.KEY_M);
        bind("Points", KeyInput.KEY_P);
        bind("Slice", KeyInput.KEY_SPACE);
        inputManager.addListener((com.jme3.input.controls.ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            if (name.equals("Model")) {
                toggle(model);
            } else if (name.equals("Points")) {
                toggle(points);
            } else if (name.equals("Slice")) {
                toggle(plane);
            } else {
                if (name.equals("Previous") || name.equals("Next")) {
                    slice = Math.max(0, Math.min(size(axis) - 1, slice + (name.equals("Next") ? 1 : -1)));
                } else {
                    axis = name.equals("X") ? 0 : name.equals("Y") ? 1 : 2;
                    slice = size(axis) / 2;
                }
                updateSlice();
            }
            updateHelp();
        }, "X", "Y", "Z", "Previous", "Next", "Model", "Points", "Slice");
    }

    private void updateSlice() {
        int u = axis == 0 ? 1 : 0;
        int v = axis == 2 ? 1 : 2;
        int width = size(u);
        int height = size(v);
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        float range = sdf.getCellSize().length() * 3;
        int[] index = new int[3];
        index[axis] = slice;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                index[u] = x;
                index[v] = y;
                float d = sdf.getDistance(index[0], index[1], index[2]);
                int fade = Math.round(255 * (1 - Math.min(1, Math.abs(d) / range)));
                pixels.put((byte) (d < 0 ? 255 : fade));
                pixels.put((byte) fade);
                pixels.put((byte) (d < 0 ? fade : 255));
                pixels.put((byte) 255);
            }
        }
        pixels.flip();
        // 释放被替换的旧图像，避免反复移动切片积累显存；纹理对象本身复用。
        Image old = sliceTexture.getImage();
        sliceTexture.setImage(new Image(Image.Format.RGBA8, width, height, pixels, ColorSpace.Linear));
        if (old != null) {
            old.dispose();
        }
        float[] vertices = new float[12];
        for (int corner = 0; corner < 4; corner++) {
            Vector3f p = sdf.getMin();
            p.set(axis, p.get(axis) + slice * sdf.getCellSize().get(axis));
            p.set(u, (corner == 1 || corner == 2) ? sdf.getMax().get(u) : sdf.getMin().get(u));
            p.set(v, corner >= 2 ? sdf.getMax().get(v) : sdf.getMin().get(v));
            model.localToWorld(p, p);
            vertices[corner * 3] = p.x;
            vertices[corner * 3 + 1] = p.y;
            vertices[corner * 3 + 2] = p.z;
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, vertices);
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, new float[]{0, 0, 1, 0, 1, 1, 0, 1});
        mesh.setBuffer(VertexBuffer.Type.Index, 3, new int[]{0, 1, 2, 0, 2, 3});
        mesh.updateBound();
        plane.setMesh(mesh);
    }

    private void createPoints() {
        float[] positions = new float[sdf.getSizeX() * sdf.getSizeY() * sdf.getSizeZ() * 3];
        int count = 0;
        Vector3f cell = sdf.getCellSize();
        Vector3f min = sdf.getMin();
        float band = cell.length() * .5f;
        for (int z = 0; z < sdf.getSizeZ(); z++) {
            for (int y = 0; y < sdf.getSizeY(); y++) {
                for (int x = 0; x < sdf.getSizeX(); x++) {
                    if (Math.abs(sdf.getDistance(x, y, z)) <= band) {
                        Vector3f p = new Vector3f(min.x + x * cell.x, min.y + y * cell.y, min.z + z * cell.z);
                        model.localToWorld(p, p);
                        positions[count++] = p.x;
                        positions[count++] = p.y;
                        positions[count++] = p.z;
                    }
                }
            }
        }
        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Points);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, Arrays.copyOf(positions, count));
        mesh.updateBound();
        points = new Geometry("Near-surface samples", mesh);
        Material material = unshaded();
        material.setColor("Color", ColorRGBA.Yellow);
        material.setFloat("PointSize", 3);
        points.setMaterial(material);
        rootNode.attachChild(points);
    }

    private Material unshaded() {
        return new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
    }

    private int size(int dimension) {
        return dimension == 0 ? sdf.getSizeX() : dimension == 1 ? sdf.getSizeY() : sdf.getSizeZ();
    }

    private void bind(String name, int key) {
        inputManager.addMapping(name, new KeyTrigger(key));
    }

    private static void toggle(Spatial spatial) {
        spatial.setCullHint(spatial.getCullHint() == Spatial.CullHint.Always
                ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    private void updateHelp() {
        help.setText("SDF " + sdf.getSignMode() + " | " + "XYZ".charAt(axis) + " slice " + slice + "/" + (size(axis) - 1)
                + "\nX/Y/Z axis | Left/Right slice | M model | P samples | Space plane"
                + "\nWhite: near surface | Blue: positive | Red: negative | Yellow: surface band"
                + "\nMouse drag + WASD/RF: camera | Slice preview: bottom right");
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (smoke && ++frame % 10 == 0) {
            axis = (axis + 1) % 3;
            slice = size(axis) / 2;
            updateSlice();
            updateHelp();
            if (frame == 40) {
                System.out.println("SDF_VISUALIZATION_PASS mode=" + sdf.getSignMode());
                stop();
            }
        }
    }
}
