package jme3test.gpuDrivenRendering;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import jme3tools.optimize.LodGenerator;

/**
 * Offline CPU LOD bake for the real GPU benchmark assets. Entry/verification:
 * gradlew :jme3-examples:bakeGpuDrivenLods. No graphics context is required.
 * Creates Tank/LodTank.j3o, Boat/LodBoat.j3o and Buggy/LodBuggy.j3o in testdata;
 * never overwrites originals or existing outputs. Existing outputs are reloaded
 * and validated. Materials, vertex attributes and LOD0 are retained. Reductions
 * target 50%, 80%, 95%, 99% removed triangles; topology can prevent those targets.
 * Inspect silhouettes in the interactive example before shipping these presets.
 *
 * <pre>{@code
 * // 第一步：离线加载副本；不要对正在渲染或多个模型共享的 mesh 原地烘焙。
 * Spatial copy = assetManager.loadModel("Models/Tank/tank.j3o").clone(true);
 * // 第二步：遍历 Geometry，每个共享 Mesh 只处理一次。
 * LodGenerator generator = new LodGenerator(geometry);
 * generator.bakeLods(LodGenerator.TriangleReductionMethod.PROPORTIONAL,
 *         .5f, .8f, .95f, .99f); // 参数为移除比例；LOD0 自动保留原始索引。
 * // 第三步：BinaryExporter 会把 Mesh 的所有 LOD 索引一起写进 j3o。
 * try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
 *     BinaryExporter.getInstance().save(copy, out);
 * }
 * // 第四步：重新加载验证。烘焙只生成数据，运行时还必须启用 LOD 选择。
 * Spatial loaded = assetManager.loadModel("Models/Tank/LodTank.j3o");
 * // 普通路径可用 LodControl；GPU Scene 使用 setLodThresholds，不能仅加载就期待减面。
 * }</pre>
 */
public final class BakeGpuDrivenLods {
    public static void main(String[] args) throws Exception {
        DesktopAssetManager assets = new DesktopAssetManager(true);
        Path base = Paths.get("jme3-testdata/src/main/resources/Models");
        if (!Files.isDirectory(base)) {
            base = Paths.get("../jme3-testdata/src/main/resources/Models");
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("Run from repository or jme3-examples");
        }
        String[][] models = {{"Tank/tank.j3o", "Tank/LodTank.j3o"},
            {"Boat/boat.j3o", "Boat/LodBoat.j3o"}, {"Buggy/Buggy.j3o", "Buggy/LodBuggy.j3o"}};
        for (String[] model : models) {
            Path output = base.resolve(model[1]);
            if (!Files.exists(output)) {
                Spatial copy = assets.loadModel("Models/" + model[0]).clone(true);
                Set<Mesh> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                bake(copy, seen);
                try (OutputStream stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                    BinaryExporter.getInstance().save(copy, stream);
                }
            }
            BinaryImporter importer = BinaryImporter.getInstance();
            importer.setAssetManager(assets);
            Spatial restored = (Spatial) importer.load(output.toFile());
            validate(restored);
            System.out.println("LOD_RELOAD_PASS " + output.toAbsolutePath());
        }
    }

    private static void bake(Spatial spatial, Set<Mesh> seen) {
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            Mesh mesh = geometry.getMesh();
            if (seen.add(mesh) && mesh.getMode() == Mesh.Mode.Triangles
                    && mesh.getBuffer(VertexBuffer.Type.Index) != null) {
                System.out.println("LOD_INPUT " + geometry.getName() + " triangles="
                        + mesh.getTriangleCount() + " existingLevels=" + mesh.getNumLodLevels());
                new LodGenerator(geometry).bakeLods(LodGenerator.TriangleReductionMethod.PROPORTIONAL,
                        .5f, .8f, .95f, .99f);
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                bake(child, seen);
            }
        }
    }

    private static void validate(Spatial spatial) {
        if (spatial instanceof Geometry) {
            Mesh mesh = ((Geometry) spatial).getMesh();
            int previous = Integer.MAX_VALUE;
            for (int level = 0; level < mesh.getNumLodLevels(); level++) {
                if (mesh.getLodLevel(level) == null) {
                    throw new AssertionError("Missing LOD " + level);
                }
                int triangles = mesh.getTriangleCount(level);
                if (triangles <= 0 || triangles > previous) {
                    throw new AssertionError("Invalid LOD triangle count");
                }
                previous = triangles;
                System.out.println("LOD_OUTPUT " + spatial.getName() + " level=" + level + " triangles=" + triangles);
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                validate(child);
            }
        }
    }
}
