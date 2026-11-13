package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.*;
import com.jme3.scene.instancing.InstancedNode;
import com.jme3.scene.plugins.sdf.*;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 10,000 GPU-instanced baked Boat/Buggy/Tree/Tank model instances: 9,900 static,
 * 100 rigid movers. Multi-part models retain all submeshes/materials. Deterministic
 * seed, distinct positions/rotations/nonuniform scales; no runtime asset baking.
 * Run gradlew :jme3-examples:bakedWorldPerformance after bakePerformanceModels.
 * GPU verification: gradlew :jme3-examples:bakedWorldPerformanceSmoke.
 * This switches views, then measures moving scenes with GDF/atlas independently
 * frozen, followed by all-enabled moving/static phases and separate static
 * model, model+atlas, GDF, GDF+atlas views. This separates update cost from preview cost. Each phase discards its
 * first frames. These are short local measurements, not a universal FPS target.
 * Lost-focus throttling is disabled for this benchmark; other applications retain
 * their normal policy. All-enabled measurements keep all 100 movers active.
 * OpenGL 3.3, no Vulkan/compute required.
 *
 * <p>Space: model/global distance field. F1: atlas panels. M: motion. G: enable/freeze
 * distance-field updates. P: enable/freeze attribute residency. C: cascade colors.
 * T: move between two locations. Drag + WASD/QZ: camera. Updates run in model view
 * too unless explicitly frozen; HUD marks freeze states. Atlas panels show current
 * diffuse, local normals, emission and normalized depth. Existing forward rendering
 * still uses original model materials, not the surface cache as a replacement material.</p>
 *
 * <p>Default GDF is three 64-cubed R32F clipmaps, 0.5/1/2 world-unit spacing, CPU
 * incremental composition with dirty voxel masks, spatial bins and brick uploads.
 * This deliberately exposes CPU update
 * cost; it is not a GPU-compute performance claim. Atlas uses 1024-squared textures,
 * 28 MiB GPU, plus equal CPU staging; source model textures/memory are additional.
 * All 60 cards of the four shared assets fit; instances reuse those cards rather
 * than uploading a separate copy per object. Initial streaming takes 15 frames
 * at four cards per frame. The coarse GDF preview is not the original mesh surface.
 * Source Tank remains 32x24x32 unsigned; other baked sources are 64-cubed unsigned.
 * Tree leaves are alpha-cutout in the new asset; SDF includes full leaf triangles,
 * so geometry tracing cannot reproduce texture cutouts. No DDGI lighting is computed.</p>
 *
 * <pre>{@code
 * // 第一步：只加载新烘焙资产，运行时不重新 bake。
 * Spatial asset = assetManager.loadModel("Models/Boat/BakeBoat.j3o");
 * SdfVolume sdf = asset.getUserData(SdfVolume.USER_DATA_KEY);
 * SurfaceBakeData surfaces = asset.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * // 第二步：验证烘焙数据后，再给材质开启 UseInstancing；只改变提交方式，不改变属性。
 * // 本例移除资产动画 Control，测试的是刚体运动，不能用静态烘焙数据代表骨骼变形。
 * if (surfaces.validate(asset, "performance-assets-v1") != SurfaceBakeData.Status.VALID) {
 *     throw new IllegalStateException("Stale baked asset");
 * }
 * enableInstancing(asset); // 本例辅助方法：递归设置材质 UseInstancing 并移除动画 Control。
 * InstancedNode chunk = new InstancedNode("Spatial chunk");
 * world.attachChild(chunk);
 * Spatial instance = asset.clone(false);
 * instance.setUserData(SdfVolume.USER_DATA_KEY, sdf);
 * instance.setUserData(SurfaceBakeData.USER_DATA_KEY, surfaces);
 * instance.setLocalTranslation(10, 0, 20);
 * instance.setLocalScale(1.2f, 0.8f, 1.1f);
 * instance.rotate(0, 0.7f, 0);
 * chunk.attachChild(instance);
 * // 第三步：实例加入后仅执行一次 instance()，不要每帧重新分组。
 * chunk.instance();
 * GlobalDistanceField global = new GlobalDistanceField(64, 3, 0.5f);
 * SurfaceAttributeAtlas atlas = new SurfaceAttributeAtlas(1024, 100, 4);
 * atlas.setUploadByteBudget(512 * 1024);
 * // 第四步：每帧移动少量模型根，更新世界变换，再更新 GDF 和属性图集。
 * rootNode.updateGeometricState();
 * global.update(world, cam.getLocation(), renderer);
 * // renderer 重载仅上传脏块；两参数重载兼容旧调用，但仍使用整级延迟上传。
 * // 静态场景距离被单独缓存；首次发生变换的实例自动转入动态合并，无需手动分类。
 * // HUD 的 index/compose/submit 是 CPU 阶段耗时，transfer 是实际提交字节数。
 * // 相机跨体素格移动、首次分配或上下文恢复时允许整级上传。
 * atlas.update(world, cam.getLocation(), renderer);
 * // 第五步：Space 只切换显示，不自动停掉更新；G/P 可分别冻结以对比成本。
 * // 更新耗时是 CPU wall time；GPU 渲染耗时应使用 RenderDoc/GPU profiler 单独测量。
 * // 第六步：卸载时先移除消费者，再 close 两个缓存，释放纹理和场景监听。
 * }</pre>
 */
public final class TestBakedWorldPerformance extends SimpleApplication {
    private static final int COUNT = 10000;
    private final Node world = new Node("Baked world");
    private final List<Spatial> moving = new ArrayList<>();
    private final List<Vector3f> origins = new ArrayList<>();
    private final List<Quaternion> rotations = new ArrayList<>();
    private GlobalDistanceField global;
    private SurfaceAttributeAtlas atlas;
    private Geometry globalScreen;
    private Material globalMaterial;
    private final Node atlasPanels = new Node("Live surface atlas panels");
    private BitmapText label;
    private boolean showGlobal;
    private boolean showAtlas = true;
    private boolean motion = true;
    private boolean updateGlobal = true;
    private boolean updateAtlas = true;
    private boolean cascadeColors;
    private boolean alternate;
    private boolean smoke;
    private int frame;
    private int batches;
    private float time;
    private double globalMs;
    private double atlasMs;
    private double sceneMs;
    private double frameMs;
    private final double[] benchmark = new double[54];
    private final int[] samples = new int[9];

    public static void main(String[] args) {
        TestBakedWorldPerformance app = new TestBakedWorldPerformance();
        app.smoke = Arrays.asList(args).contains("--smoke");
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setResolution(1280, 800);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setFrameRate(-1);
        settings.setTitle("10,000 baked instances | Boat / Buggy / Tree / Tank");
        app.setSettings(settings);
        app.setShowSettings(false);
        // 性能测试切到 IDE 查看日志时也保持运行，避免默认失焦限帧污染结果。
        app.setLostFocusBehavior(com.jme3.app.LostFocusBehavior.Disabled);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        String[] paths = {"Boat/BakeBoat.j3o", "Buggy/BakeBuggy.j3o", "Tree/BakeTree.j3o", "Tank/BakeTank.j3o"};
        Spatial[] assets = new Spatial[4];
        float[] scales = new float[4];
        for (int i = 0; i < assets.length; i++) {
            assets[i] = assetManager.loadModel("Models/" + paths[i]);
            SdfVolume sdf = assets[i].getUserData(SdfVolume.USER_DATA_KEY);
            SurfaceBakeData surface = assets[i].getUserData(SurfaceBakeData.USER_DATA_KEY);
            String revision = i == 3 ? "tank-demo-v1" : "performance-assets-v1";
            if (sdf == null || surface == null || !surface.isCompatibleWith(sdf)
                    || surface.validate(assets[i], revision) != SurfaceBakeData.Status.VALID) {
                throw new IllegalStateException("Missing/stale baked asset: " + paths[i]);
            }
            Vector3f extent = sdf.getMax().subtract(sdf.getMin());
            scales[i] = 5f / Math.max(extent.x, Math.max(extent.y, extent.z));
            enableInstancing(assets[i]);
        }
        rootNode.attachChild(world);
        Random random = new Random(51831);
        for (int cz = 0; cz < 10; cz++) {
            for (int cx = 0; cx < 10; cx++) {
                InstancedNode chunk = new InstancedNode("Chunk " + cx + "," + cz);
                world.attachChild(chunk);
                for (int z = 0; z < 10; z++) {
                    for (int x = 0; x < 10; x++) {
                        int gx = cx * 10 + x;
                        int gz = cz * 10 + z;
                        int type = (gx + gz * 3) % 4;
                        Spatial instance = assets[type].clone(false);
                        instance.setUserData(SdfVolume.USER_DATA_KEY, assets[type].getUserData(SdfVolume.USER_DATA_KEY));
                        instance.setUserData(SurfaceBakeData.USER_DATA_KEY, assets[type].getUserData(SurfaceBakeData.USER_DATA_KEY));
                        float base = scales[type];
                        instance.setLocalScale(base * (.7f + random.nextFloat() * .6f),
                                base * (.7f + random.nextFloat() * .6f), base * (.7f + random.nextFloat() * .6f));
                        instance.setLocalRotation(new Quaternion().fromAngles((random.nextFloat() - .5f) * .15f,
                                random.nextFloat() * FastMath.TWO_PI, (random.nextFloat() - .5f) * .15f));
                        instance.setLocalTranslation((gx - 50) * 10 + random.nextFloat(),
                                random.nextFloat() * .3f, (gz - 50) * 10 + random.nextFloat());
                        chunk.attachChild(instance);
                        if (x == 0 && z == 0) {
                            moving.add(instance);
                            origins.add(instance.getLocalTranslation().clone());
                            rotations.add(instance.getLocalRotation().clone());
                        }
                    }
                }
                chunk.instance();
                verifyGrouped(chunk);
                for (Spatial child : chunk.getChildren()) {
                    if (child instanceof com.jme3.scene.instancing.InstancedGeometry) {
                        batches++;
                    }
                }
            }
        }
        if (moving.size() != 100 || batches == 0) {
            throw new AssertionError("Instancing/motion setup failed");
        }
        DirectionalLight sun = new DirectionalLight();
        sun.setDirection(new Vector3f(-.5f, -1, -.3f).normalizeLocal());
        sun.setColor(ColorRGBA.White.mult(2));
        rootNode.addLight(sun);
        cam.setFrustumPerspective(60, 1280f / 800, .1f, 350);
        positionCamera();
        flyCam.setMoveSpeed(35);
        flyCam.setDragToRotate(true);
        setDisplayStatView(false);
        global = new GlobalDistanceField(64, 3, .5f);
        atlas = new SurfaceAttributeAtlas(1024, 100, 4);
        atlas.setRetentionMargin(10);
        atlas.setUploadByteBudget(512 * 1024);
        globalMaterial = new Material(assetManager, "Materials/Sdf/Global.j3md");
        for (int i = 0; i < 3; i++) {
            globalMaterial.setTexture("Field" + i, global.getTexture(i));
        }
        globalMaterial.setVector3("Cells", new Vector3f(.5f, 1, 2));
        globalMaterial.setFloat("Resolution", 64);
        globalMaterial.setBoolean("CascadeColors", false);
        globalScreen = new Geometry("Global field fullscreen", new Quad(1, 1));
        globalScreen.setMaterial(globalMaterial);
        guiNode.attachChild(globalScreen);
        for (int i = 0; i < 4; i++) {
            Material panel = new Material(assetManager, "Materials/Sdf/AtlasPreview.j3md");
            panel.setTexture("AttributeAtlas", i == 3 ? atlas.getDepthTexture()
                    : atlas.getTexture(SurfaceAttributeCache.Channel.values()[i]));
            panel.setTexture("DepthAtlas", atlas.getDepthTexture());
            panel.setVector4("AtlasRect", new Vector4f(0, 0, 1, 1));
            panel.setInt("Mode", i == 3 ? 4 : (i == 1 ? 1 : 0));
            panel.setBoolean("Missing", false);
            Geometry quad = new Geometry("Live atlas " + i, new Quad(230, 230));
            quad.setMaterial(panel);
            quad.setLocalTranslation(10 + i * 240, 20, 2);
            atlasPanels.attachChild(quad);
        }
        guiNode.attachChild(atlasPanels);
        label = new BitmapText(guiFont);
        label.setLocalTranslation(10, 790, 3);
        guiNode.attachChild(label);
        String[] names = {"View", "Atlas", "Motion", "Global", "Residency", "Colors", "Travel"};
        int[] keys = {KeyInput.KEY_SPACE, KeyInput.KEY_F1, KeyInput.KEY_M, KeyInput.KEY_G,
            KeyInput.KEY_P, KeyInput.KEY_C, KeyInput.KEY_T};
        for (int i = 0; i < names.length; i++) {
            inputManager.addMapping(names[i], new KeyTrigger(keys[i]));
        }
        inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
            if (!pressed) {
                return;
            }
            switch (name) {
                case "View":
                    showGlobal = !showGlobal;
                    break;
                case "Atlas":
                    showAtlas = !showAtlas;
                    break;
                case "Motion":
                    motion = !motion;
                    break;
                case "Global":
                    updateGlobal = !updateGlobal;
                    break;
                case "Residency":
                    updateAtlas = !updateAtlas;
                    break;
                case "Colors":
                    cascadeColors = !cascadeColors;
                    break;
                case "Travel":
                    alternate = !alternate;
                    positionCamera();
                    break;
                default:
                    break;
            }
        }, names);
        System.out.println("BAKED_WORLD_SETUP instances=" + COUNT + " static=9900 moving=100 batches=" + batches);
    }

    private void verifyGrouped(Spatial spatial) {
        if (spatial instanceof com.jme3.scene.instancing.InstancedGeometry) {
            return;
        }
        if (spatial instanceof Geometry) {
            if (!((Geometry) spatial).isGrouped()) {
                throw new AssertionError("Source geometry was not instanced: " + spatial.getName());
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                verifyGrouped(child);
            }
        }
    }

    private void enableInstancing(Spatial spatial) {
        while (spatial.getNumControls() != 0) {
            spatial.removeControl(spatial.getControl(0));
        }
        if (spatial instanceof Geometry) {
            ((Geometry) spatial).getMaterial().setBoolean("UseInstancing", true);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                enableInstancing(child);
            }
        }
    }

    private void positionCamera() {
        float offset = alternate ? 150 : 0;
        cam.setLocation(new Vector3f(offset, 12, 24));
        cam.lookAt(new Vector3f(offset, 0, -40), Vector3f.UNIT_Y);
    }

    @Override
    public void simpleUpdate(float tpf) {
        frame++;
        // Exclude initialization and first-frame resource upload from the moving average.
        frameMs = frame <= 3 ? tpf * 1000 : frameMs * .9 + tpf * 100;
        time += smoke ? .016f : Math.min(tpf, .1f);
        long start = System.nanoTime();
        if (motion) {
            for (int i = 0; i < moving.size(); i++) {
                Spatial instance = moving.get(i);
                instance.setLocalTranslation(origins.get(i).add((float) Math.sin(time + i) * 2, 0,
                        (float) Math.cos(time * .7f + i) * 2));
                instance.setLocalRotation(rotations.get(i).mult(new Quaternion().fromAngleAxis(time * .2f, Vector3f.UNIT_Y)));
            }
        }
        if (smoke && frame == 18) {
            showGlobal = true;
            showAtlas = false;
        }
        if (smoke && frame == 24) {
            alternate = true;
            positionCamera();
            cascadeColors = true;
        }
        if (smoke && frame == 32) {
            alternate = false;
            positionCamera();
            showGlobal = false;
            showAtlas = false;
        }
        if (smoke && frame >= 110) {
            int phase = Math.min(8, (frame - 10) / 100);
            motion = phase < 5;
            updateGlobal = phase == 2 || phase >= 4;
            updateAtlas = phase == 1 || phase >= 4;
            if (phase >= 5) {
                showGlobal = phase >= 7;
                showAtlas = phase == 6 || phase == 8;
            }
        }
        rootNode.updateGeometricState();
        sceneMs = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        if (updateGlobal) {
            global.update(world, cam.getLocation(), renderer);
        }
        globalMs = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        if (updateAtlas) {
            atlas.update(world, cam.getLocation(), renderer);
        }
        atlasMs = (System.nanoTime() - start) / 1e6;
        for (int i = 0; i < 3; i++) {
            globalMaterial.setVector3("Min" + i, global.getMin(i));
        }
        globalMaterial.setMatrix4("ClipToWorld", cam.getViewProjectionMatrix().invert());
        globalMaterial.setBoolean("CascadeColors", cascadeColors);
        world.setCullHint(showGlobal ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
        globalScreen.setCullHint(showGlobal ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        atlasPanels.setCullHint(showAtlas ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        label.setText(String.format(java.util.Locale.ROOT,
                "10,000 model instances | 9,900 static + 100 moving | instancing groups=%d | frame %.2f ms\n"
                + "CPU scene %.2f ms | GDF %.2f ms [%s] | atlas %.2f ms [%s] | motion=%s\n"
                + "GDF nearby=%d resampled=%d | atlas resident=%d missing=%d upload=%d KiB | indexed=%d candidates=%d\n"
                + "GDF CPU index %.2f / compose %.2f / submit %.2f ms | transfer=%d KiB\n"
                + "Space: model/GDF | F1: atlas | M: motion | G/P: freeze GDF/atlas | C: cascades | T: travel\n"
                + "CPU-composed unsigned GDF; Tree alpha holes are absent. Atlas: diffuse / normal / emission / depth.",
                batches, frameMs, sceneMs, globalMs, updateGlobal ? "live" : "frozen", atlasMs,
                updateAtlas ? "live" : "frozen", motion, global.getInstanceCount(), global.getUpdatedVoxelCount(),
                atlas.getResidentCardCount(), atlas.getMissingCardCount(), atlas.getLastTransferBytes() / 1024,
                atlas.getIndexedInstanceCount(), atlas.getIndexQueryCandidateCount(),
                global.getIndexNanos() / 1e6, global.getCompositionNanos() / 1e6,
                global.getUploadNanos() / 1e6, global.getUploadedBytes() / 1024));
        if (smoke && frame >= 50 && frame <= 900 && frame % 100 >= 30) {
            int phase = Math.min(8, (frame - 10) / 100);
            samples[phase]++;
            benchmark[phase * 6] += tpf * 1000;
            benchmark[phase * 6 + 1] += globalMs;
            benchmark[phase * 6 + 2] += atlasMs;
            benchmark[phase * 6 + 3] += sceneMs;
            benchmark[phase * 6 + 4] += updateGlobal ? global.getIndexNanos() / 1e6 : 0;
            benchmark[phase * 6 + 5] += updateGlobal ? global.getCompositionNanos() / 1e6 : 0;
            if (updateAtlas && atlas.getLastTransferBytes() != 0) {
                throw new AssertionError("Rigid motion must reuse resident surface pixels");
            }
            if (phase >= 5 && (global.getUpdatedVoxelCount() != 0 || global.getUploadedBytes() != 0)) {
                throw new AssertionError("Static scene must not rebuild/upload cached data");
            }
        }
        if (smoke && frame > 1 && atlas.getIndexedInstanceCount() != COUNT) {
            throw new AssertionError("Instance index count mismatch");
        }
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (smoke && (frame == 16 || frame == 22 || frame == 28)) {
            screenshot();
            System.out.println("BAKED_WORLD_SAMPLE frame=" + frame + " gdfMs=" + globalMs + " atlasMs=" + atlasMs
                    + " nearby=" + global.getInstanceCount() + " residentCards=" + atlas.getResidentCardCount()
                    + " gdfUploadBytes=" + global.getUploadedBytes());
        }
        if (smoke && frame == 902) {
            if (atlas.getResidentCardCount() != 60 || atlas.getMissingCardCount() != 0
                    || global.getInstanceCount() == 0) {
                throw new AssertionError("Missing runtime baked resources");
            }
            String[] phases = {"all-moving-cold", "no-gdf", "no-atlas", "both-frozen", "all-moving-warm", "static-model",
                "static-model-atlas", "static-gdf", "static-gdf-atlas"};
            for (int phase = 0; phase < 9; phase++) {
                System.out.println("BAKED_WORLD_MEAN phase=" + phases[phase] + " samples=" + samples[phase]
                        + " frameMs=" + benchmark[phase * 6] / samples[phase]
                        + " gdfMs=" + benchmark[phase * 6 + 1] / samples[phase]
                        + " atlasMs=" + benchmark[phase * 6 + 2] / samples[phase]
                        + " sceneMs=" + benchmark[phase * 6 + 3] / samples[phase]
                        + " indexMs=" + benchmark[phase * 6 + 4] / samples[phase]
                        + " composeMs=" + benchmark[phase * 6 + 5] / samples[phase]);
            }
            System.out.println("BAKED_WORLD_PERFORMANCE_PASS");
            stop();
        }
    }

    private void screenshot() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(1280 * 800 * 4);
        renderer.readFrameBufferWithFormat(null, bytes, Image.Format.RGBA8);
        BufferedImage image = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
        int visible = 0;
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 1280; x++) {
                int offset = (x + y * 1280) * 4;
                int r = bytes.get(offset) & 255;
                int g = bytes.get(offset + 1) & 255;
                int b = bytes.get(offset + 2) & 255;
                image.setRGB(x, 799 - y, (r << 16) | (g << 8) | b);
                if (y > 270 && y < 650 && r + g + b > 100) {
                    visible++;
                }
            }
        }
        if (visible < 100) {
            throw new AssertionError("Empty performance view");
        }
        try {
            Path base = Paths.get("build", "sdf");
            Files.createDirectories(base);
            Path file = Files.createTempDirectory(base, "baked-world-").resolve("preview.png");
            ImageIO.write(image, "png", file.toFile());
            System.out.println("BAKED_WORLD_PREVIEW " + file.toAbsolutePath());
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
        if (global != null) {
            global.close();
        }
        if (atlas != null) {
            atlas.close();
        }
        super.destroy();
    }
}
