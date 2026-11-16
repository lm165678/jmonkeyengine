package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.List;

/** Surface samples in free space. Grid indices are storage bins, not probe positions. */
final class GiSurfaceProbeLayout {
    static final int BIN_CAPACITY = 8;

    static Vector3f[] plan(Spatial scene, GiProbeGrid grid, Vector3f origin,
            float clearance, float relativeClearance) {
        Vector3f spacing = grid.getSpacing();
        Vector3f counts = grid.getCounts();
        float gap = Math.max(clearance, Math.min(spacing.x, Math.min(spacing.y, spacing.z)) * relativeClearance);
        float separation = Math.min(spacing.x, Math.min(spacing.y, spacing.z)) * 0.35f;
        GiSurfaceCoverage check = new GiSurfaceCoverage(scene, GiProbePlacement.validator(scene), gap, 0);
        List<List<Vector3f>> surfaces = new ArrayList<>();
        long[] work = {0};
        scene.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof Geometry) || !GiProbePlacement.participates(spatial)) {
                return;
            }
            Geometry geometry = (Geometry) spatial;
            Mesh mesh = geometry.getMesh();
            if (mesh.getMode() != Mesh.Mode.Triangles && mesh.getMode() != Mesh.Mode.TriangleStrip
                    && mesh.getMode() != Mesh.Mode.TriangleFan) {
                return;
            }
            int[] islands = GiProbeSolidClassifier.connectedComponents(mesh);
            java.util.Map<Integer, List<Vector3f>> patches = new java.util.TreeMap<>();
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f c = new Vector3f();
            for (int triangle = 0; triangle < mesh.getTriangleCount(); triangle++) {
                mesh.getTriangle(triangle, a, b, c);
                geometry.localToWorld(a, a);
                geometry.localToWorld(b, b);
                geometry.localToWorld(c, c);
                Vector3f normal = b.subtract(a).cross(c.subtract(a));
                if (normal.lengthSquared() < 1e-16f) {
                    continue;
                }
                normal.normalizeLocal();
                List<Vector3f> candidates = patches.computeIfAbsent(islands[triangle], key -> new ArrayList<>());
                float longest = Math.max(a.distance(b), Math.max(b.distance(c), c.distance(a)));
                int steps = Math.max(1, (int) Math.ceil(longest / separation));
                if (steps > 1000 || work[0] + (long) (steps + 1) * (steps + 2) > 1_000_000) {
                    throw new IllegalArgumentException("Surface layout sampling budget exceeded; split the volume");
                }
                for (int y = 0; y <= steps; y++) {
                    for (int x = 0; x <= steps - y; x++) {
                        work[0] += 2;
                        Vector3f surface = a.mult(1f - (float) (x + y) / steps)
                                .addLocal(b.mult((float) x / steps)).addLocal(c.mult((float) y / steps));
                        for (int side : new int[]{1, -1}) {
                            Vector3f n = normal.mult(side);
                            GiSurfaceCoverage.Target target = check.target(surface, n, geometry, spacing);
                            if (target != null && bin(target.position.subtract(origin), spacing, counts) >= 0) {
                                candidates.add(target.position);
                            }
                        }
                    }
                }
            }
            for (List<Vector3f> patch : patches.values()) {
                // Stable shuffled dart throwing avoids consuming a small budget
                // at the first triangle/edge of a large connected surface.
                java.util.Collections.shuffle(patch, new java.util.Random(0x4753504cL));
                surfaces.add(patch);
            }
        });
        // Interleave independent surfaces so large merged meshes cannot consume
        // the whole budget before a small disconnected object is considered.
        @SuppressWarnings("unchecked")
        List<Vector3f>[] bins = new List[grid.getProbeCount()];
        List<Vector3f> selected = new ArrayList<>();
        int[] cursors = new int[surfaces.size()];
        boolean progressed = true;
        while (progressed && selected.size() < grid.getProbeCount()) {
            progressed = false;
            for (int surface = 0; surface < surfaces.size() && selected.size() < grid.getProbeCount(); surface++) {
                List<Vector3f> candidates = surfaces.get(surface);
                while (cursors[surface] < candidates.size()) {
                    Vector3f point = candidates.get(cursors[surface]++);
                    int index = bin(point.subtract(origin), spacing, counts);
                    if (bins[index] == null) {
                        bins[index] = new ArrayList<>();
                    }
                    if (bins[index].size() >= BIN_CAPACITY || nearby(point, bins, origin, spacing, counts, separation, check)) {
                        continue;
                    }
                    bins[index].add(point);
                    selected.add(point);
                    progressed = true;
                    break;
                }
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No reachable surfaces inside GI coverage");
        }
        Vector3f[] result = new Vector3f[grid.getProbeCount()];
        for (int i = 0; i < selected.size(); i++) {
            result[i] = selected.get(i);
        }
        return result;
    }

    private static boolean nearby(Vector3f point, List<Vector3f>[] bins, Vector3f origin,
            Vector3f spacing, Vector3f counts, float separation, GiSurfaceCoverage check) {
        Vector3f coordinate = point.subtract(origin).divideLocal(spacing);
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    int bx = (int) coordinate.x + x;
                    int by = (int) coordinate.y + y;
                    int bz = (int) coordinate.z + z;
                    if (bx < 0 || by < 0 || bz < 0 || bx >= counts.x || by >= counts.y || bz >= counts.z) {
                        continue;
                    }
                    List<Vector3f> neighbors = bins[bx + (int) counts.x * (by + (int) counts.y * bz)];
                    if (neighbors == null) {
                        continue;
                    }
                    for (Vector3f other : neighbors) {
                        // Opposite sides of a thin wall are distinct demands even
                        // when Euclidean distance is smaller than the sample spacing.
                        if (point.distanceSquared(other) < separation * separation && check.visible(point, other)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    static int bin(Vector3f point, Vector3f spacing, Vector3f counts) {
        Vector3f q = point.divide(spacing);
        if (!Vector3f.isValidVector(q) || q.x < 0 || q.y < 0 || q.z < 0
                || q.x > counts.x - 1 || q.y > counts.y - 1 || q.z > counts.z - 1) {
            return -1;
        }
        return (int) q.x + (int) counts.x * ((int) q.y + (int) counts.y * (int) q.z);
    }
}
