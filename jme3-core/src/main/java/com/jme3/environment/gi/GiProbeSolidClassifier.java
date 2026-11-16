package com.jme3.environment.gi;

import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Bake-snapshot topology classification, independent of vertex normal winding.
 * Welds coincident positions and identifies closed connected triangle components.
 * Open sheets never count as solids. Rebuild after geometry changes; not shared
 * globally or used on animated live meshes. Non-manifold components stay with
 * the caller's conservative open-surface placement policy.
 */
final class GiProbeSolidClassifier {
    private final Map<Geometry, int[]> components = new IdentityHashMap<>();
    private static final Vector3f[] RAYS = {
        new Vector3f(1, 0.371f, 0.529f).normalizeLocal(),
        new Vector3f(-0.417f, 1, 0.231f).normalizeLocal(),
        new Vector3f(0.197f, -0.613f, 1).normalizeLocal()
    };

    GiProbeSolidClassifier(Spatial scene) {
        Map<Mesh, int[]> topology = new IdentityHashMap<>();
        scene.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry && GiProbePlacement.participates(spatial)) {
                Geometry geometry = (Geometry) spatial;
                Mesh mesh = geometry.getMesh();
                if (mesh.getMode() == Mesh.Mode.Triangles || mesh.getMode() == Mesh.Mode.TriangleStrip
                        || mesh.getMode() == Mesh.Mode.TriangleFan) {
                    components.put(geometry, topology.computeIfAbsent(mesh, GiProbeSolidClassifier::closedComponents));
                }
            }
        });
    }

    boolean inside(Vector3f point) {
        for (Map.Entry<Geometry, int[]> entry : components.entrySet()) {
            Geometry geometry = entry.getKey();
            if (geometry.getWorldBound() == null || !geometry.getWorldBound().contains(point)) {
                continue;
            }
            int votes = 0;
            for (Vector3f direction : RAYS) {
                CollisionResults hits = new CollisionResults();
                geometry.collideWith(new Ray(point, direction), hits);
                Map<Integer, Float> previous = new HashMap<>();
                Map<Integer, Integer> crossings = new HashMap<>();
                for (int i = 0; i < hits.size(); i++) {
                    CollisionResult hit = hits.getCollision(i);
                    int triangle = hit.getTriangleIndex();
                    if (triangle < 0 || triangle >= entry.getValue().length) {
                        continue;
                    }
                    int component = entry.getValue()[triangle];
                    if (component < 0) {
                        continue;
                    }
                    Float last = previous.get(component);
                    float distance = hit.getDistance();
                    if (last == null || Math.abs(distance - last) > Math.max(1.0e-6f, Math.ulp(distance) * 4)) {
                        crossings.merge(component, 1, Integer::sum);
                        previous.put(component, distance);
                    }
                }
                if (crossings.values().stream().anyMatch(count -> (count & 1) != 0)) {
                    votes++;
                }
            }
            if (votes >= 2) {
                return true;
            }
        }
        return false;
    }

    private static int[] closedComponents(Mesh mesh) {
        return components(mesh, true);
    }

    /** Connected surface islands, including open sheets, for bake planning. */
    static int[] connectedComponents(Mesh mesh) {
        return components(mesh, false);
    }

    private static int[] components(Mesh mesh, boolean closedOnly) {
        int count = mesh.getTriangleCount();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        Map<Vector3f, Integer> vertices = new HashMap<>();
        Map<Long, Edge> edges = new HashMap<>();
        Vector3f[] triangle = {new Vector3f(), new Vector3f(), new Vector3f()};
        for (int t = 0; t < count; t++) {
            mesh.getTriangle(t, triangle[0], triangle[1], triangle[2]);
            int[] ids = new int[3];
            for (int v = 0; v < 3; v++) {
                Vector3f key = triangle[v].clone();
                // Signed zero represents the same geometric position.
                key.set(key.x == 0 ? 0 : key.x, key.y == 0 ? 0 : key.y, key.z == 0 ? 0 : key.z);
                ids[v] = vertices.computeIfAbsent(key, ignored -> vertices.size());
            }
            for (int e = 0; e < 3; e++) {
                int a = Math.min(ids[e], ids[(e + 1) % 3]);
                int b = Math.max(ids[e], ids[(e + 1) % 3]);
                long key = ((long) a << 32) | (b & 0xffffffffL);
                Edge edge = edges.get(key);
                if (edge == null) {
                    edges.put(key, new Edge(t));
                } else {
                    parent[root(parent, t)] = root(parent, edge.triangle);
                    edge.count++;
                }
            }
        }
        boolean[] open = new boolean[count];
        for (Edge edge : edges.values()) {
            if (edge.count != 2) {
                open[root(parent, edge.triangle)] = true;
            }
        }
        for (int i = 0; i < count; i++) {
            parent[i] = root(parent, i);
        }
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = closedOnly && open[parent[i]] ? -1 : parent[i];
        }
        return result;
    }

    private static int root(int[] parent, int node) {
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    private static final class Edge {
        final int triangle;
        int count = 1;

        Edge(int triangle) {
            this.triangle = triangle;
        }
    }
}
