package com.jme3.environment.gi;

import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.jme3.renderer.queue.RenderQueue;

/**
 * Places probes against scene geometry on the CPU. Closed connected mesh components use three-ray
 * parity voting, independent of face winding. Exact nearest triangle distance
 * enforces clearance at faces, edges and vertices. Relocation is bounded to 45%
 * of each grid step and cannot cross a surface when starting in free space.
 * Every move is revalidated. Unresolved probes are disabled. Open components
 * enforce two-sided clearance but have no inferred solid interior. A custom
 * validator is needed for non-manifold solids or GPU-only deformation.
 * Sky/GUI buckets and explicitly culled geometry are excluded. This policy never
 * changes source geometry or existing SDF data.
 *
 * <pre>{@code
 * // 第一步：默认使用闭合三角网格检测；在渲染线程固定场景姿态。
 * GiSceneBaker.Settings settings = new GiSceneBaker.Settings();
 * settings.classifyProbes = true;
 * // 第二步：薄墙附近至少保持一定距离，移动量始终限制在原网格单元内。
 * settings.probeClearance = 0.1f;
 * // 第三步：有可靠的签名 SDF 时，可通过 settings.validator 替换此启发式。
 * }</pre>
 */
public final class GiProbePlacement {
    private GiProbePlacement() {
    }


    /** Returns validity and writes the relocated world position to store. Call on the scene thread.
     * The baker caches topology for its immutable snapshot. This standalone
     * convenience method rebuilds topology; use the baker for a complete grid.
     * Open sheets are not classified as solid objects.
     */
    public static boolean place(Spatial scene, Vector3f origin, Vector3f spacing,
            float clearance, Vector3f store) {
        return validator(scene).place(scene, origin, spacing, clearance, store);
    }

    static GiSceneBaker.ProbeValidator validator(Spatial scene) {
        GiProbeSolidClassifier solids = new GiProbeSolidClassifier(scene);
        return (snapshot, origin, spacing, clearance, store) ->
                place(snapshot, origin, spacing, clearance, store, solids);
    }

    private static boolean place(Spatial scene, Vector3f origin, Vector3f spacing,
            float clearance, Vector3f store, GiProbeSolidClassifier solids) {
        if (!Vector3f.isValidVector(origin) || !Vector3f.isValidVector(spacing)
                || spacing.x <= 0 || spacing.y <= 0 || spacing.z <= 0
                || !Float.isFinite(clearance) || clearance <= 0) {
            throw new IllegalArgumentException("Finite positive spacing and clearance required");
        }
        store.set(origin);
        for (int attempt = 0; attempt < 8; attempt++) {
            Vector3f closest = new Vector3f();
            Vector3f nearestNormal = new Vector3f();
            float exactDistance = nearestSurface(scene, store, clearance, closest, nearestNormal);
            boolean inside = solids.inside(store);
            if (!inside && exactDistance >= clearance) {
                return true;
            }
            if (attempt == 7) {
                return false;
            }
            Vector3f next;
            if (inside) {
                float reach = spacing.length() * 0.45f;
                Vector3f boundary = new Vector3f();
                float distance = nearestSurface(scene, store, reach, boundary, new Vector3f());
                if (distance >= reach || distance < 1.0e-8f) {
                    return false;
                }
                // Geometric exit direction does not depend on triangle winding.
                next = boundary.add(boundary.subtract(store).normalizeLocal().mult(clearance * 1.1f));
            } else {
                Vector3f away = store.subtract(closest);
                if (away.lengthSquared() < 1.0e-12f) {
                    away.set(nearestNormal);
                }
                next = closest.add(away.normalizeLocal().mult(clearance * 1.1f));
            }
            if (!inside) {
                Vector3f travel = next.subtract(store);
                float length = travel.length();
                if (length > 1.0e-7f) {
                    CollisionResults crossings = new CollisionResults();
                    Ray path = new Ray(store, travel.divide(length));
                    path.setLimit(length);
                    scene.collideWith(path, crossings);
                    for (int i = 0; i < crossings.size(); i++) {
                        CollisionResult crossing = crossings.getCollision(i);
                        if (participates(crossing.getGeometry()) && crossing.getDistance() > 1.0e-6f
                                && crossing.getDistance() < length) {
                            return false;
                        }
                    }
                }
            }
            Vector3f delta = next.subtract(origin);
            if (Math.abs(delta.x) > spacing.x * 0.45f || Math.abs(delta.y) > spacing.y * 0.45f
                    || Math.abs(delta.z) > spacing.z * 0.45f) {
                return false;
            }
            store.set(next);
        }
        return false;
    }

    // Exact world-space nearest point, including triangle edges missed by rays.
    // Distant geometry bounds are rejected before visiting triangle positions.
    static float nearestSurface(Spatial scene, Vector3f point, float radius,
            Vector3f closest, Vector3f normal) {
        float[] best = {radius * radius};
        scene.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof com.jme3.scene.Geometry) || !participates(spatial)) {
                return;
            }
            com.jme3.scene.Geometry geometry = (com.jme3.scene.Geometry) spatial;
            if (geometry.getWorldBound() != null && geometry.getWorldBound().distanceToEdge(point) > radius) {
                return;
            }
            com.jme3.scene.Mesh mesh = geometry.getMesh();
            if (mesh.getMode() != com.jme3.scene.Mesh.Mode.Triangles
                    && mesh.getMode() != com.jme3.scene.Mesh.Mode.TriangleStrip
                    && mesh.getMode() != com.jme3.scene.Mesh.Mode.TriangleFan) {
                return;
            }
            Vector3f a = new Vector3f();
            Vector3f b = new Vector3f();
            Vector3f c = new Vector3f();
            for (int i = 0; i < mesh.getTriangleCount(); i++) {
                mesh.getTriangle(i, a, b, c);
                geometry.localToWorld(a, a);
                geometry.localToWorld(b, b);
                geometry.localToWorld(c, c);
                Vector3f n = b.subtract(a).cross(c.subtract(a));
                if (n.lengthSquared() < 1.0e-16f) {
                    continue;
                }
                n.normalizeLocal();
                Vector3f q = point.subtract(n.mult(point.subtract(a).dot(n)));
                if (b.subtract(a).cross(q.subtract(a)).dot(n) < 0
                        || c.subtract(b).cross(q.subtract(b)).dot(n) < 0
                        || a.subtract(c).cross(q.subtract(c)).dot(n) < 0) {
                    q = segment(point, a, b);
                    Vector3f other = segment(point, b, c);
                    if (other.distanceSquared(point) < q.distanceSquared(point)) {
                        q = other;
                    }
                    other = segment(point, c, a);
                    if (other.distanceSquared(point) < q.distanceSquared(point)) {
                        q = other;
                    }
                }
                float distance = q.distanceSquared(point);
                if (distance < best[0]) {
                    best[0] = distance;
                    closest.set(q);
                    normal.set(n);
                }
            }
        });
        return (float) Math.sqrt(best[0]);
    }

    private static Vector3f segment(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f edge = b.subtract(a);
        float t = p.subtract(a).dot(edge) / Math.max(edge.lengthSquared(), 1.0e-20f);
        return a.add(edge.mult(Math.max(0, Math.min(1, t))));
    }

    // Use scene surfaces as placement samples in both bake modes.
    static boolean participates(Spatial spatial) {
        RenderQueue.Bucket bucket = spatial.getQueueBucket();
        if (bucket == RenderQueue.Bucket.Sky || bucket == RenderQueue.Bucket.Gui
                || bucket == RenderQueue.Bucket.Transparent || bucket == RenderQueue.Bucket.Translucent) {
            return false;
        }
        for (Spatial ancestor = spatial; ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor.getLocalCullHint() == Spatial.CullHint.Always) {
                return false;
            }
        }
        return true;
    }

}
