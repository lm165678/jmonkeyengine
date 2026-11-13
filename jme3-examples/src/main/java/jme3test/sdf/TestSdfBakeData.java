package jme3test.sdf;

import com.jme3.asset.AssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.plugins.sdf.*;
import com.jme3.scene.VertexBuffer;
import com.jme3.system.JmeSystem;
import java.nio.file.*;
import java.io.OutputStream;

/**
 * SDK bake/save/load/revision workflow, no graphics context required.
 * Run main or gradlew :jme3-examples:sdfBakeDataSmoke.
 * Creates a NEW build/sdf/bake-data-... directory, never overwrites source assets.
 * Demonstrates geometry/material invalidation separately, not probe lighting.
 */
public final class TestSdfBakeData {
    public static void main(String[] args) throws Exception {
        AssetManager assets = JmeSystem.newAssetManager(TestSdfBakeData.class.getResource("/com/jme3/asset/Desktop.cfg"));
        Geometry model = (Geometry) assets.loadModel("Models/Tank/tank.j3o");
        Material material = assets.loadMaterial("Models/Tank/tank.j3m");
        // 本例把材质编辑结果嵌入 j3o；否则 Geometry 加载时会优先重新读取外部 j3m。
        material.setKey(null);
        material.setColor("BaseColor", ColorRGBA.Red);
        model.setMaterial(material);

        // 第一步：SDK 用户点击“烘焙”。这里的版本字符串仅是演示；正式工具应使用外部依赖内容哈希。
        SdfBakeData data = SdfBakeData.bake(model,
                new SdfSettings(24, 24, 24, .05f, SdfSettings.SignMode.UNSIGNED), "demo-assets-v1", null);
        // 第二步：成功后挂载；保存的是 SDF + 布局/映射 + 依赖记录，不包含 GPU 缓存和灯光。
        data.attachTo(model);
        Path base = Paths.get("build", "sdf");
        Files.createDirectories(base);
        Path output = Files.createTempDirectory(base, "bake-data-").resolve("Tank-with-surface-layout.j3o");
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            BinaryExporter.getInstance().save(model, stream);
        }

        // 第三步：加载原生 j3o，从 userdata 直接取回。没有再次运行生成器。
        BinaryImporter importer = BinaryImporter.getInstance();
        importer.setAssetManager(assets);
        Geometry loaded = (Geometry) importer.load(output.toFile());
        SdfBakeData restored = loaded.getUserData(SdfBakeData.USER_DATA_KEY);
        check(restored.validate(loaded, "demo-assets-v1") == SdfBakeData.Status.VALID,
                "round trip validation: " + restored.validate(loaded, "demo-assets-v1"));
        check(restored.getLayout().getCardCount() == 6, "six capture cards");

        // 第四步：仅改变材质，SDF 和布局仍可复用；SDK 显示“表面属性需更新”。
        loaded.getMaterial().setColor("BaseColor", ColorRGBA.Green);
        check(restored.validate(loaded, "demo-assets-v1") == SdfBakeData.Status.MATERIAL_CHANGED, "material change");
        SdfVolume previous = restored.getVolume();
        restored.refreshMaterials(loaded, "demo-assets-v1");
        check(restored.getVolume() == previous, "material refresh must not rebake SDF");

        // 第五步：外部贴图内容变化但路径不变，也必须通过新的依赖内容版本通知系统。
        check(restored.validate(loaded, "demo-assets-v2") == SdfBakeData.Status.MATERIAL_CHANGED, "external revision");
        // 第六步：模型顶点变化，SDK 必须重新烘焙；旧缓存不能继续使用。
        java.nio.FloatBuffer positions = (java.nio.FloatBuffer) loaded.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
        positions.put(0, positions.get(0) + 0.1f);
        check(restored.validate(loaded, "demo-assets-v1") == SdfBakeData.Status.GEOMETRY_CHANGED, "geometry change");
        System.out.println("SDF_BAKE_DATA_PASS " + output.toAbsolutePath());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
