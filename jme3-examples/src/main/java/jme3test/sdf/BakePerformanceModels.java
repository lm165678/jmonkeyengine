package jme3test.sdf;

import com.jme3.app.SimpleApplication;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.*;
import com.jme3.system.AppSettings;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.nio.file.*;
import java.io.OutputStream;

/**
 * Offline editor-style batch bake: Boat, Buggy, Tree -> new Bake*.j3o assets.
 * Run gradlew :jme3-examples:bakePerformanceModels. OpenGL 3.3 required for attributes.
 * Original assets and BakeTank.j3o are never overwritten. Existing valid outputs
 * are reused; an incompatible existing output fails instead of silently replacing it.
 * Source geometry is static CPU/rest geometry. Tree leaves use alpha cutout in the
 * new file; geometric unsigned SDF cannot reproduce alpha-texture holes.
 *
 * <pre>{@code
 * // 第一步：加载并克隆离线模型，编辑副本，不修改原始 j3o。
 * Spatial copy = assetManager.loadModel("Models/Boat/boat.j3o").clone(true);
 * prepare(copy); // 本例辅助方法：嵌入材质、生成切线，把树叶副本转为 alpha cutout。
 * // 第二步：CPU 生成局部 SDF；这里统一使用开放表面兼容的 UNSIGNED。
 * SdfVolume sdf = SdfGenerator.bake(copy,
 *     new SdfSettings(64, 64, 64, .05f, SdfSettings.SignMode.UNSIGNED), null);
 * // 第三步：渲染线程视口边界调用独立表面烘焙，生成布局、映射、实际像素及深度。
 * SurfaceBakeData surface = SurfaceAttributeBaker.bake(copy, "performance-assets-v1",
 *     renderManager, viewPort, 64, null);
 * // 第四步：挂载两份数据并保存新文件；重载验证后才交给性能案例使用。
 * sdf.attachTo(copy);
 * surface.attachTo(copy);
 * Path destination = Paths.get("BakeBoat.j3o");
 * try (OutputStream stream = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
 *     BinaryExporter.getInstance().save(copy, stream);
 * }
 * BinaryImporter importer = BinaryImporter.getInstance();
 * importer.setAssetManager(assetManager);
 * Spatial restored = (Spatial) importer.load(destination.toFile());
 * SurfaceBakeData loaded = restored.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * if (loaded.validate(restored, "performance-assets-v1") != SurfaceBakeData.Status.VALID) {
 *     throw new IllegalStateException("Baked asset reload validation failed");
 * }
 * // 第五步：实例化开关在运行时设置，不能在烘焙 SurfaceCapture 之前开启。
 * }</pre>
 */
public final class BakePerformanceModels extends SimpleApplication {
    private boolean done;

    public static void main(String[] args) {
        BakePerformanceModels app = new BakePerformanceModels();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL33);
        settings.setResolution(640, 480);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        setDisplayStatView(false);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (done) {
            return;
        }
        done = true;
        try {
            bake("Boat/boat.j3o", "Boat/BakeBoat.j3o");
            bake("Buggy/Buggy.j3o", "Buggy/BakeBuggy.j3o");
            bake("Tree/Tree.mesh.j3o", "Tree/BakeTree.j3o");
            System.out.println("PERFORMANCE_MODEL_BAKES_PASS");
            stop();
        } catch (Exception error) {
            throw new IllegalStateException("Model bake failed", error);
        }
    }

    private void bake(String input, String output) throws Exception {
        Spatial model = assetManager.loadModel("Models/" + input).clone(true);
        prepare(model);
        model.updateGeometricState();
        Path base = Paths.get("jme3-testdata", "src", "main", "resources", "Models");
        if (!Files.isDirectory(base)) {
            base = Paths.get("..", "jme3-testdata", "src", "main", "resources", "Models");
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("Run from repository or jme3-examples directory");
        }
        Path destination = base.resolve(output).toAbsolutePath().normalize();
        if (!Files.exists(destination)) {
            long start = System.nanoTime();
            SdfVolume sdf = SdfGenerator.bake(model,
                    new SdfSettings(64, 64, 64, .05f, SdfSettings.SignMode.UNSIGNED), null);
            SurfaceBakeData surface = SurfaceAttributeBaker.bake(model, "performance-assets-v1",
                    renderManager, viewPort, 64, null);
            sdf.attachTo(model);
            surface.attachTo(model);
            try (OutputStream stream = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                BinaryExporter.getInstance().save(model, stream);
            }
            System.out.println("BAKED " + destination + " ms=" + (System.nanoTime() - start) / 1000000);
        }
        BinaryImporter importer = BinaryImporter.getInstance();
        importer.setAssetManager(assetManager);
        Spatial restored = (Spatial) importer.load(destination.toFile());
        SurfaceBakeData surface = restored.getUserData(SurfaceBakeData.USER_DATA_KEY);
        SdfVolume sdf = restored.getUserData(SdfVolume.USER_DATA_KEY);
        if (surface == null || sdf == null || !surface.isCompatibleWith(sdf)
                || surface.validate(restored, "performance-assets-v1") != SurfaceBakeData.Status.VALID
                || surface.validate(model, "performance-assets-v1") != SurfaceBakeData.Status.VALID) {
            throw new IllegalStateException("Bake validation failed: " + destination);
        }
        System.out.println("BAKE_RELOAD_PASS " + output + " cards=" + surface.getAttributes().getCardCount());
    }

    private void prepare(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            Material material = geometry.getMaterial();
            material.setKey(null);
            if (material.getParam("AlphaDiscardThreshold") != null) {
                material.setTransparent(false);
                material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Off);
                geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
            }
            MikktspaceTangentGenerator.generate(geometry);
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                prepare(child);
            }
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
