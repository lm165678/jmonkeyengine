package jme3test.sdf;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.scene.Geometry;
import com.jme3.scene.plugins.sdf.*;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * SDK one-button workflow on Tank: two INDEPENDENT bake APIs, save, reload, upload.
 * Run gradlew :jme3-examples:surfaceAttributeBake or main; --surface-only omits SDF.
 * Automated: surfaceAttributeBakeSmoke and surfaceAttributeOnlySmoke.
 * Requires OpenGL 3.3 because surface attributes are evaluated by the original materials.
 * All output goes to new build/sdf/tank-baked-... folders; never overwrites Tank assets.
 * The displayed card textures come from the RELOADED j3o, not live capture targets.
 * Controls match TestSurfaceAttributes; R performs a fresh explicit bake/save/load.
 *
 * <pre>{@code
 * // 第一步（可选）：只做 SDF 的独立 CPU 接口。
 * SdfVolume sdf = SdfGenerator.bake(model, sdfSettings, progress);
 * sdf.attachTo(model);
 * // 第二步（可选）：在渲染线程一键完成表面布局、映射、所有属性/深度卡片。
 * // 这个入口不调用 SDF 生成器，没有 SDF 也能单独使用。
 * SurfaceBakeData surfaces = SurfaceAttributeBaker.bake(
 *         model, dependencyRevision, renderManager, viewPort, 128, progress);
 * surfaces.attachTo(model);
 * // 第三步：两份数据都有时校验几何来源；不是为每个 SDF 体素绑定材质 ID。
 * if (sdf != null && !surfaces.isCompatibleWith(sdf)) {
 *     throw new IllegalStateException("不匹配的几何版本");
 * }
 * // 第四步：普通 BinaryExporter 保存模型，两份独立 userdata 自动随 j3o 保存。
 * // 只需要距离场的工具可以完全省略第二步；只需要属性缓存的工具也可以省略第一步。
 * // 第五步：重新加载后取 SurfaceBakeData，验证 revision，再 uploadAll()。
 * SurfaceAttributeData.Textures textures = loadedSurfaces.getAttributes().uploadAll();
 * // 只上传保存的 float 数据，不遍历模型，不运行 capture；释放时 textures.close()。
 * }</pre>
 */
public final class TestSurfaceAttributeBake extends TestSurfaceAttributes {
    private boolean includeSdf = true;
    private boolean checkedCancellation;

    public static void main(String[] args) {
        TestSurfaceAttributeBake app = new TestSurfaceAttributeBake();
        app.includeSdf = !Arrays.asList(args).contains("--surface-only");
        launch(app, args);
    }

    @Override
    protected SurfaceBakeData bakeSurfaceData() {
        try {
            // 将这个预览的材质编辑内嵌，避免加载时外部 tank.j3m 覆盖尚未保存的修改。
            source.getMaterial().setKey(null);
            SdfVolume sdf = source.getUserData(SdfVolume.USER_DATA_KEY);
            if (includeSdf && sdf == null) {
                sdf = SdfGenerator.bake(source,
                        new SdfSettings(32, 24, 32, .05f, SdfSettings.SignMode.UNSIGNED), null);
                sdf.attachTo(source);
            }

            // 独立一键表面烘焙：布局 + diffuse/normal/emission + depth，内部遍历所有卡片。
            SurfaceBakeData surface = SurfaceAttributeBaker.bake(source, "tank-demo-v1",
                    renderManager, viewPort, 64, null);
            if (includeSdf && !surface.isCompatibleWith(sdf)) {
                throw new AssertionError("Independent bakes have mismatched geometry");
            }
            surface.attachTo(source);

            if (!checkedCancellation) {
                try {
                    SurfaceAttributeBaker.bake(source, "tank-demo-v1", renderManager, viewPort, 32,
                            fraction -> fraction < .2f);
                    throw new AssertionError("Cancellation ignored");
                } catch (CancellationException expected) {
                    if (source.getUserData(SurfaceBakeData.USER_DATA_KEY) != surface) {
                        throw new AssertionError("Cancelled bake replaced previous result");
                    }
                }
                checkedCancellation = true;
            }

            Path base = Paths.get("build", "sdf");
            Files.createDirectories(base);
            Path output = Files.createTempDirectory(base, "tank-baked-").resolve("Tank-with-attributes.j3o");
            try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                BinaryExporter.getInstance().save(source, stream);
            }
            BinaryImporter importer = BinaryImporter.getInstance();
            importer.setAssetManager(assetManager);
            Geometry loaded = (Geometry) importer.load(output.toFile());
            SurfaceBakeData restored = loaded.getUserData(SurfaceBakeData.USER_DATA_KEY);
            SdfVolume restoredSdf = loaded.getUserData(SdfVolume.USER_DATA_KEY);
            if (restored.validate(loaded, "tank-demo-v1") != SurfaceBakeData.Status.VALID
                    || (includeSdf ? !restored.isCompatibleWith(restoredSdf) : restoredSdf != null)) {
                throw new AssertionError("Independent userdata round trip failed");
            }
            for (int card = 0; card < surface.getAttributes().getCardCount(); card++) {
                if (!Arrays.equals(surface.getAttributes().copyCardData(card), restored.getAttributes().copyCardData(card))) {
                    throw new AssertionError("Saved surface pixel data changed");
                }
            }
            System.out.println("SURFACE_BAKE_J3O_PASS sdf=" + includeSdf + " bytes="
                    + restored.getAttributes().getByteSize() + " output=" + output.toAbsolutePath());
            // 上层预览仅从重载数据 uploadAll()，没有再次捕获模型。
            return restored;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot save/reload baked Tank", exception);
        }
    }
}
