package jme3test.sdf;

import com.jme3.asset.AssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.SdfBakeData;
import com.jme3.scene.plugins.sdf.SdfMeshSnapshot;
import com.jme3.scene.plugins.sdf.SdfSettings;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.system.JmeSystem;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Sponza 离线距离场生成及 j3o 保存/重载的完整案例，无窗口、无 GPU 上下文。
 * 从 jme3-testdata 的 Models/Sponza/Sponza.j3o 加载，绝不覆盖原始资产。
 * 运行 main() 默认 64x32x64；--smoke 为 24x12x24 快速回归。
 * 默认 UNSIGNED，适用于含开放表面的 Sponza/后续 DDGI 表面距离查询；
 * --signed 可演示 EVEN_ODD，但 Sponza 非闭合部分不保证符号正确。
 * 每次在 build/sdf/ 下新建独立目录，另存 Sponza-with-sdf.j3o 与 sdf-slice.png。
 * Gradle 命令：gradlew :jme3-examples:sponzaSdfSmoke；输出目录打印在控制台。
 * 默认分辨率：gradlew :jme3-examples:sponzaSdfBake；加 -PsdfSigned 演示奇偶符号模式。
 * j3o 中保留原模型及 SdfVolume，不嵌入外部材质纹理；加载时仍需原资产搜索路径。
 */
public final class TestSponzaSdf {
    private TestSponzaSdf() {
    }

    public static void main(String[] args) throws Exception {
        boolean smoke = Arrays.asList(args).contains("--smoke");
        boolean signed = Arrays.asList(args).contains("--signed");
        // 第一步：使用正常 AssetManager 加载测试资产，不修改磁盘原文件。
        AssetManager assets = JmeSystem.newAssetManager(
                TestSponzaSdf.class.getResource("/com/jme3/asset/Desktop.cfg"));
        Spatial model = assets.loadModel("Models/Sponza/Sponza.j3o");

        // 第二步：捕获 CPU 网格快照。SDK 中可以在场景线程做这一步，之后后台烘焙。
        long start = System.nanoTime();
        SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(model);
        System.out.println("SDF triangles=" + snapshot.getTriangleCount()
                + " skippedDegenerate=" + snapshot.getIgnoredDegenerateTriangles());
        int resolution = smoke ? 24 : 64;
        SdfSettings settings = new SdfSettings(resolution, resolution / 2, resolution, .05f,
                signed ? SdfSettings.SignMode.EVEN_ODD : SdfSettings.SignMode.UNSIGNED);

        // 第三步：生成 float 距离场；回调在当前工作线程，false 或线程中断可取消。
        int[] lastPercent = {-10};
        SdfBakeData bakeData = SdfBakeData.bake(model, settings, "sponza-demo-assets-v1", progress -> {
            int percent = Math.round(progress * 100);
            if (percent >= lastPercent[0] + 10 || percent == 100) {
                System.out.println("SDF progress " + percent + "%");
                lastPercent[0] = percent;
            }
            return true;
        });
        SdfVolume sdf = bakeData.getVolume();
        System.out.println("Surface cards=" + bakeData.getLayout().getCardCount());
        System.out.println("SDF bake seconds=" + (System.nanoTime() - start) / 1e9);

        // 第四步：只有成功后才附加到模型根节点。后台 SDK 任务需回到场景线程附加。
        // 同时保存捕获布局与依赖版本；正式 SDK 将演示版本替换为外部资产内容哈希。
        bakeData.attachTo(model);
        // DDGI 将来可以 sdf.toTexture3D() 上传；本测试不创建显卡资源。
        // shader 使用 getMin/getMax/getCellSize/getInterpolationMargin 解码距离和安全步长。

        // 第五步：新目录 + CREATE_NEW 双重保护，不覆盖原始 Sponza 或上次输出。
        Path base = Paths.get("build", "sdf").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "sponza-");
        Path output = directory.resolve("Sponza-with-sdf.j3o");
        try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            BinaryExporter.getInstance().save(model, stream);
        }

        // 第六步：重新读取新文件，确认不是仅在内存中挂了一个对象。
        BinaryImporter importer = new BinaryImporter();
        importer.setAssetManager(assets);
        Spatial loaded = (Spatial) importer.load(output.toFile());
        SdfVolume restored = loaded.getUserData(SdfVolume.USER_DATA_KEY);
        SdfBakeData restoredBake = loaded.getUserData(SdfBakeData.USER_DATA_KEY);
        if (restoredBake == null || restoredBake.validate(loaded, "sponza-demo-assets-v1") != SdfBakeData.Status.VALID) {
            throw new AssertionError("Sponza bake layout/dependency record did not round trip");
        }
        if (restored == null || !Arrays.equals(sdf.copyDistances(), restored.copyDistances())
                || !sdf.getMin().equals(restored.getMin()) || !sdf.getMax().equals(restored.getMax())
                || sdf.getSignMode() != restored.getSignMode() || model.getTriangleCount() != loaded.getTriangleCount()) {
            throw new AssertionError("SDF/model j3o round trip failed");
        }

        // 第七步：输出水平中间切片；近表面白色，正距离蓝色，负距离红色。
        BufferedImage image = new BufferedImage(sdf.getSizeX(), sdf.getSizeZ(), BufferedImage.TYPE_INT_RGB);
        float range = sdf.getMax().subtract(sdf.getMin()).length() * .05f;
        for (int z = 0; z < sdf.getSizeZ(); z++) {
            for (int x = 0; x < sdf.getSizeX(); x++) {
                float distance = sdf.getDistance(x, sdf.getSizeY() / 2, z);
                int shade = Math.round(255 * (1 - Math.min(1, Math.abs(distance) / range)));
                int rgb = distance < 0 ? (255 << 16) | (shade << 8) | shade
                        : (shade << 16) | (shade << 8) | 255;
                image.setRGB(x, sdf.getSizeZ() - 1 - z, rgb);
            }
        }
        ImageIO.write(image, "png", directory.resolve("sdf-slice.png").toFile());
        System.out.println("SPONZA_SDF_PASS samples=" + sdf.copyDistances().length + " mode=" + sdf.getSignMode());
        System.out.println("SDF output=" + output);
    }
}
