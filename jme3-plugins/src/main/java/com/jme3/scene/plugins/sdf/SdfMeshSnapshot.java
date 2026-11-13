package com.jme3.scene.plugins.sdf;

import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Immutable triangle snapshot, independent of live scene state and graphics contexts.
 * Coordinates are relative to the supplied root: its own transform and ancestors are
 * excluded, while every descendant local transform is applied. All triangle Geometry
 * children are included regardless of cull hints/materials. Lines/points are skipped.
 * Positions are current CPU Position buffers: GPU skinning, morphing, instancing and
 * material displacement/alpha cutouts are NOT evaluated. Bake the desired CPU pose first.
 */
public final class SdfMeshSnapshot {
    final double[][] triangles;
    private final int ignoredDegenerateTriangles;

    private SdfMeshSnapshot(List<double[]> triangles, int ignored) {
        this.triangles = triangles.toArray(new double[triangles.size()][]);
        ignoredDegenerateTriangles = ignored;
    }

    /**
     * Copies model triangles without modifying transforms, meshes, controls or bounds.
     * Call on the scene-owning thread or while the source is not being modified.
     * Result can be shared by concurrent background bakes.
     * <pre>{@code
     * // 第一步：渲染线程上捕获快照，不要让后台线程同时读取正在变形的模型。
     * SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(model);
     * // 第二步：后台任务只使用快照，不再访问 live model。
     * executor.submit(() -> SdfGenerator.generate(snapshot, settings, progress));
     * // 离线 SDK 模型没有并发修改时，也可直接在工作线程 capture。
     * }</pre>
     * @param root model root whose local coordinate system defines the volume
     * @return immutable snapshot
     */
    public static SdfMeshSnapshot capture(Spatial root) {
        Objects.requireNonNull(root, "root");
        List<double[]> triangles = new ArrayList<>();
        int[] ignored = {0};
        collect(root, new Matrix4f(), triangles, ignored);
        if (triangles.isEmpty()) {
            throw new IllegalArgumentException("Model contains no non-degenerate triangles");
        }
        return new SdfMeshSnapshot(triangles, ignored[0]);
    }

    /** @return captured non-degenerate triangle count */
    public int getTriangleCount() {
        return triangles.length;
    }

    /** @return zero-area triangle count skipped during capture */
    public int getIgnoredDegenerateTriangles() {
        return ignoredDegenerateTriangles;
    }

    String geometryHash() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(9 * Double.BYTES);
            for (double[] triangle : triangles) {
                cancelled();
                bytes.clear();
                for (double coordinate : triangle) {
                    bytes.putDouble(coordinate == 0 ? 0 : coordinate);
                }
                digest.update(bytes.array());
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 255));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void collect(Spatial spatial, Matrix4f transform, List<double[]> output, int[] ignored) {
        cancelled();
        if (spatial instanceof Geometry) {
            Mesh mesh = ((Geometry) spatial).getMesh();
            if (mesh != null && (mesh.getMode() == Mesh.Mode.Triangles
                    || mesh.getMode() == Mesh.Mode.TriangleStrip || mesh.getMode() == Mesh.Mode.TriangleFan)) {
                Vector3f a = new Vector3f();
                Vector3f b = new Vector3f();
                Vector3f c = new Vector3f();
                for (int i = 0; i < mesh.getTriangleCount(); i++) {
                    cancelled();
                    mesh.getTriangle(i, a, b, c);
                    transform.mult(a, a);
                    transform.mult(b, b);
                    transform.mult(c, c);
                    double[] t = {a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z};
                    for (double coordinate : t) {
                        if (!Double.isFinite(coordinate)) {
                            throw new IllegalArgumentException("Non-finite transformed vertex in " + spatial.getName());
                        }
                    }
                    if (SdfBvh.areaSquared(t) == 0) {
                        ignored[0]++;
                    } else {
                        output.add(t);
                    }
                }
            }
        }
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                collect(child, transform.mult(child.getLocalTransform().toTransformMatrix()), output, ignored);
            }
        }
    }

    static void cancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("SDF generation interrupted");
        }
    }
}
