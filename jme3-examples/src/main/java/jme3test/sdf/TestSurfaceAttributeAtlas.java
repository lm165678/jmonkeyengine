package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.plugins.sdf.*;
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
 * Five Tank instances, mixed 32/64-pixel saved cards, one bounded shared atlas set.
 * Run gradlew :jme3-examples:surfaceAttributeAtlas or main. OpenGL 3.3 required.
 * Automated: gradlew :jme3-examples:surfaceAttributeAtlasSmoke.
 * Spatial-index stress: gradlew :jme3-examples:surfaceSceneIndexSmoke
 * (interactive: surfaceSceneIndex, IDEA: --index-stress). Adds 10,000 distant
 * instances sharing the baked data; HUD reports indexed/visited/query counts.
 * Tab switches between two clusters; F selects a card; B toggles 512/128 atlas budget.
 * Drag + WASD/QZ moves the camera. Bottom: whole diffuse atlas, whole normal atlas,
 * selected card's accepted surface (green), rejected behind-surface point (red).
 * Checkerboard means not resident, not a black/default material sampled by mistake.
 *
 * <p>The initial 64-pixel data comes from BakeTank.j3o. To demonstrate different
 * dimensions/material versions, startup bakes two colored 32-pixel Tank variants
 * once through SurfaceAttributeBaker. No assets are overwritten. Camera movement
 * only copies saved pixels; it never rebakes. Two near instances share the same
 * 64-pixel data; a 32-pixel asset is shared across both clusters. No DDGI is run.</p>
 * <pre>{@code
 * // 第一步：从 j3o 获取属性；为克隆显式共享同一只读 SurfaceBakeData。
 * Geometry model = (Geometry) assetManager.loadModel("Models/Tank/BakeTank.j3o");
 * SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * Geometry clone = model.clone(false);
 * clone.setUserData(SurfaceBakeData.USER_DATA_KEY, data);
 * // 第二步：图集预算与场景物体数无关；512 边长的四通道图集合计 7 MiB。
 * SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(512, 22f, 3);
 * atlas.setUploadByteBudget(256 * 1024);
 * atlas.setRetentionMargin(2f);
 * // 第三步：每帧变换更新后迁入近处卡片；不同尺寸按实际矩形分配，包含一像素保护边。
 * // 三参数 update 通过 Renderer 局部上传；首次创建/上下文恢复才整图上传。
 * // 不支持局部上传的后端自动退回整图路径，静止帧不提交上传。
 * rootNode.updateGeometricState();
 * atlas.update(rootNode, cam.getLocation(), renderer);
 * // 自动场景索引：首次全量发现，后续通过场景通知只更新变更分支。
 * // 相机移动仅查询邻近网格；setLocalTranslation/attach/detach/setUserData 自动生效。
 * // getIndexVisitedNodeCount() 静态帧应为零；候选数不应随远处物体总数增长。
 * // 第四步：绑定同一组纹理；每个查询只更换 UV 区域和世界到卡片的变换。
 * SurfaceAttributeAtlas.Region region = atlas.getRegion(model, 4);
 * if (region != null) {
 *     material.setVector4("AtlasRect", region.getUvTransform());
 *     material.setMatrix4("WorldToCard", atlas.getWorldToCard(model, 4));
 * }
 * // 第五步：缺失区域用显式回退。本例用棋盘格，不能保留上一帧已经被淘汰的矩形。
 * // 第六步：ShaderLib/SurfaceAtlasLookup.glsllib 提供 world-hit -> UV + depth 校验。
 * // 第七步：卸载或更换预算时移除旧消费者并 close，随后重新绑定新图集。
 * }</pre>
 */
public final class TestSurfaceAttributeAtlas extends SimpleApplication {
    private final Node models = new Node("Atlas instances");
    private final Geometry[] tanks = new Geometry[5];
    private final Material[] panels = new Material[4];
    private SurfaceAttributeAtlas atlas;
    private BitmapText label;
    private boolean ready;
    private boolean far;
    private boolean smallBudget;
    private boolean smoke;
    private boolean indexStress;
    private Geometry stressMoving;
    private int frame;
    private int card = 4;

    public static void main(String[] args) {
        TestSurfaceAttributeAtlas app = new TestSurfaceAttributeAtlas();
        app.smoke = Arrays.asList(args).contains("--smoke");
        app.indexStress = Arrays.asList(args).contains("--index-stress");
        AppSettings settings = new AppSettings(true);
        settings.setResolution(1100, 800);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setAudioRenderer(null);
        settings.setTitle("Bounded surface attribute atlas / mixed card sizes");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        rootNode.attachChild(models);
        DirectionalLight light = new DirectionalLight();
        light.setDirection(new Vector3f(-1, -2, -1).normalizeLocal());
        light.setColor(ColorRGBA.White.mult(3));
        rootNode.addLight(light);
        cam.setViewPort(0, 1, .36f, 1);
        cam.setFrustumPerspective(60, 1100f / (800 * .64f), .05f, 200);
        moveCamera();
        flyCam.setMoveSpeed(12);
        flyCam.setDragToRotate(true);
        setDisplayStatView(false);
        label = new BitmapText(guiFont);
        label.setLocalTranslation(10, 790, 1);
        label.setText("Preparing mixed-size Tank surface assets...");
        guiNode.attachChild(label);
        for (int i = 0; i < panels.length; i++) {
            Material material = new Material(assetManager, "Materials/Sdf/AtlasPreview.j3md");
            material.setBoolean("Missing", true);
            material.setInt("Mode", i);
            material.setVector4("AtlasRect", new Vector4f(0, 0, 1, 1));
            Geometry quad = new Geometry("Atlas panel " + i, new Quad(260, 260));
            quad.setLocalTranslation(10 + i * 275, 20, 0);
            quad.setMaterial(material);
            guiNode.attachChild(quad);
            panels[i] = material;
        }
        inputManager.addMapping("Cluster", new KeyTrigger(KeyInput.KEY_TAB));
        inputManager.addMapping("Card", new KeyTrigger(KeyInput.KEY_F));
        inputManager.addMapping("Budget", new KeyTrigger(KeyInput.KEY_B));
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed || !ready) {
                return;
            }
            if (name.equals("Cluster")) {
                far = !far;
                moveCamera();
            } else if (name.equals("Card")) {
                card = (card + 1) % 6;
            } else {
                smallBudget = !smallBudget;
                replaceAtlas();
            }
        }, "Cluster", "Card", "Budget");
    }

    private void moveCamera() {
        float center = far ? 50 : 0;
        cam.setLocation(new Vector3f(center, 8, 13));
        cam.lookAt(new Vector3f(center, 0, 0), Vector3f.UNIT_Y);
    }

    private Geometry variant(Geometry source, ColorRGBA color) {
        Geometry result = source.clone(true);
        result.getMaterial().setColor("BaseColor", color);
        result.getMaterial().setKey(null);
        SurfaceAttributeBaker.bake(result, "atlas-demo-v1", renderManager, viewPort, 32, null).attachTo(result);
        return result;
    }

    private void prepare() {
        Geometry source = (Geometry) assetManager.loadModel("Models/Tank/BakeTank.j3o");
        Geometry green = variant(source, new ColorRGBA(.3f, 1, .3f, 1));
        Geometry red = variant(source, new ColorRGBA(1, .3f, .3f, 1));
        Geometry[] assets = {source, source, green, red, green};
        float[] positions = {-5, 1, 6, 46, 54};
        SdfVolume sdf = source.getUserData(SdfVolume.USER_DATA_KEY);
        Vector3f extent = sdf.getMax().subtract(sdf.getMin());
        float scale = 5 / Math.max(extent.x, Math.max(extent.y, extent.z));
        for (int i = 0; i < tanks.length; i++) {
            Geometry tank = assets[i].clone(false);
            tank.setUserData(SurfaceBakeData.USER_DATA_KEY, assets[i].getUserData(SurfaceBakeData.USER_DATA_KEY));
            tank.setLocalScale(scale * (1 + i * .05f), scale, scale);
            tank.setLocalTranslation(positions[i], 0, 0);
            tank.rotate(0, i * .35f, 0);
            tanks[i] = tank;
            models.attachChild(tank);
        }
        if (indexStress) {
            // 万实例只共享资产，不重复烘焙或上传属性纹理。
            for (int group = 0; group < 100; group++) {
                Node cluster = new Node("Distant cluster " + group);
                models.attachChild(cluster);
                for (int j = 0; j < 100; j++) {
                    Geometry distant = source.clone(false);
                    distant.setUserData(SurfaceBakeData.USER_DATA_KEY,
                            source.getUserData(SurfaceBakeData.USER_DATA_KEY));
                    distant.setLocalScale(scale);
                    distant.setLocalTranslation(1000 + j * 20, 0, group * 20);
                    cluster.attachChild(distant);
                    stressMoving = distant;
                }
            }
        }
        rootNode.updateGeometricState();
        replaceAtlas();
        ready = true;
    }

    private void replaceAtlas() {
        if (atlas != null) {
            for (Material material : panels) {
                material.clearParam("AttributeAtlas");
                material.clearParam("DepthAtlas");
            }
            atlas.close();
        }
        atlas = new SurfaceAttributeAtlas(smallBudget ? 128 : 512, 22, 3);
        atlas.setUploadByteBudget(256 * 1024);
        atlas.setRetentionMargin(2f);
        for (int i = 0; i < panels.length; i++) {
            panels[i].setTexture("AttributeAtlas", atlas.getTexture(i == 1
                    ? SurfaceAttributeCache.Channel.NORMAL : SurfaceAttributeCache.Channel.DIFFUSE));
            panels[i].setTexture("DepthAtlas", atlas.getDepthTexture());
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!ready) {
            return;
        }
        frame++;
        if (smoke && (frame == 12 || frame == 22)) {
            far = !far;
            moveCamera();
        }
        if (smoke && frame == 30) {
            smallBudget = true;
            replaceAtlas();
        }
        if (smoke && indexStress && frame == 6) {
            stressMoving.move(1, 0, 0);
        }
        rootNode.updateGeometricState();
        atlas.update(models, cam.getLocation(), renderer);
        if (smoke && indexStress && frame > 1 && frame < 30) {
            int visited = atlas.getIndexVisitedNodeCount();
            if (atlas.getIndexedInstanceCount() != 10005 || atlas.getIndexQueryCandidateCount() > 10
                    || visited > (frame == 6 ? 2 : 0)) {
                throw new AssertionError("Spatial index performed unbounded work: " + visited);
            }
            if (frame == 6 || frame == 8) {
                System.out.println("SURFACE_INDEX_WORK total=" + atlas.getIndexedInstanceCount()
                        + " visited=" + visited + " candidates=" + atlas.getIndexQueryCandidateCount());
            }
        }
        if (smoke && (frame == 2 || frame == 12)) {
            System.out.println("ATLAS_TRANSFER frame=" + frame + " bytes=" + atlas.getLastTransferBytes());
            if (atlas.getLastTransferBytes() >= atlas.getTextureBytes()) {
                throw new AssertionError("Expected partial upload after initialization");
            }
        }
        if (smoke && (frame == 8 || frame == 18 || frame == 28)) {
            if (atlas.getResidentCardCount() != 12 || atlas.getUploadedCardCount() != 0 || atlas.getLastTransferBytes() != 0) {
                throw new AssertionError("Shared assets should settle at 12 resident cards");
            }
            if (!far && atlas.getRegion(tanks[0], 4) != atlas.getRegion(tanks[1], 4)) {
                throw new AssertionError("Instances duplicated surface storage");
            }
        }
        if (smoke && frame == 36 && atlas.getMissingCardCount() == 0) {
            throw new AssertionError("Small atlas should exercise missing-card fallback");
        }
        Geometry selected = far ? tanks[3] : tanks[0];
        SurfaceAttributeAtlas.Region region = atlas.getRegion(selected, card);
        for (int i = 0; i < panels.length; i++) {
            panels[i].setBoolean("Missing", i >= 2 && region == null);
            if (i >= 2 && region != null) {
                Matrix4f worldToCard = atlas.getWorldToCard(selected, card);
                panels[i].setVector4("AtlasRect", region.getUvTransform());
                panels[i].setFloat("CardResolution", region.getResolution());
                panels[i].setMatrix4("WorldToCard", worldToCard);
                panels[i].setMatrix4("CardToWorld", worldToCard.invert());
            }
        }
        label.setText("Shared surface atlas | " + atlas.getTextureBytes() / (1024 * 1024f)
                + " MiB | resident=" + atlas.getResidentCardCount() + " missing=" + atlas.getMissingCardCount()
                + " transferKiB=" + atlas.getLastTransferBytes() / 1024
                + " uploaded=" + atlas.getUploadedCardCount() + " evicted=" + atlas.getEvictedCardCount()
                + " | indexed=" + atlas.getIndexedInstanceCount()
                + " visited=" + atlas.getIndexVisitedNodeCount()
                + " candidates=" + atlas.getIndexQueryCandidateCount()
                + "\nTab: switch cluster | F: card " + card + " | B: atlas budget | Drag + WASD/QZ"
                + "\nBottom: diffuse atlas / normal atlas / valid hit green / rejected hit red");
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!ready) {
            prepare();
        } else if (smoke && (frame == 8 || frame == 18 || frame == 36)) {
            screenshot(frame != 36);
        } else if (smoke && frame == 40) {
            System.out.println("SURFACE_ATTRIBUTE_ATLAS_PASS");
            stop();
        }
    }

    private void screenshot(boolean validate) {
        ByteBuffer bytes = BufferUtils.createByteBuffer(1100 * 800 * 4);
        renderer.readFrameBufferWithFormat(null, bytes, Image.Format.RGBA8);
        BufferedImage image = new BufferedImage(1100, 800, BufferedImage.TYPE_INT_RGB);
        int green = 0;
        int red = 0;
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 1100; x++) {
                int offset = (x + y * 1100) * 4;
                int r = bytes.get(offset) & 255;
                int g = bytes.get(offset + 1) & 255;
                int b = bytes.get(offset + 2) & 255;
                image.setRGB(x, 799 - y, (r << 16) | (g << 8) | b);
                if (y > 20 && y < 280 && x > 560 && x < 820 && g > 200 && r < 20) {
                    green++;
                }
                if (y > 20 && y < 280 && x > 835 && r > 200 && g < 20) {
                    red++;
                }
            }
        }
        if (validate && (green < 100 || red < 100)) {
            throw new AssertionError("Atlas world/depth projection failed: " + green + "," + red);
        }
        try {
            Path base = Paths.get("build", "sdf");
            Files.createDirectories(base);
            Path file = Files.createTempDirectory(base, "attribute-atlas-").resolve("preview.png");
            ImageIO.write(image, "png", file.toFile());
            System.out.println("SURFACE_ATLAS_PREVIEW " + file.toAbsolutePath());
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
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
        guiNode.detachAllChildren();
        if (atlas != null) {
            atlas.close();
        }
        super.destroy();
    }
}
