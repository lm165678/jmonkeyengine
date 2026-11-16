package com.jme3.environment.gi;

import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;

/** Scene-thread surface-demand checks for adaptive brick planning. No GPU data. */
final class GiSurfaceCoverage {
    static final class Target {
        final Vector3f position;
        final Vector3f normal;
        final Object surface;
        double weight;
        boolean covered;

        Target(Vector3f position, Vector3f normal, Object surface) {
            this.position = position;
            this.normal = normal;
            this.surface = surface;
        }
    }

    final Spatial scene;
    final GiProbeSolidClassifier solids;
    final GiSceneBaker.ProbeValidator placement;
    final float clearance;
    final float relativeClearance;
    int rejected;

    GiSurfaceCoverage(Spatial scene, GiSceneBaker.ProbeValidator placement,
            float clearance, float relativeClearance) {
        this.scene = scene;
        this.solids = new GiProbeSolidClassifier(scene);
        this.placement = placement;
        this.clearance = clearance;
        this.relativeClearance = relativeClearance;
    }

    float clearance(Vector3f spacing) {
        return Math.max(clearance, Math.min(spacing.x, Math.min(spacing.y, spacing.z)) * relativeClearance);
    }

    Target target(Vector3f surface, Vector3f normal, Object id, Vector3f spacing) {
        float gap = clearance(spacing);
        Vector3f point = surface.add(normal.mult(gap * 1.1f));
        // A surface side inside a solid must not be "repaired" through the solid
        // to the opposite side. Open sheets may legitimately have two free sides.
        if (solids.inside(point)) {
            rejected++;
            return null;
        }
        Vector3f adjusted = new Vector3f();
        if (!placement.place(scene, point, spacing, gap, adjusted)
                || !visible(surface.add(normal.mult(gap * 0.01f)), adjusted)) {
            rejected++;
            return null;
        }
        return new Target(adjusted.clone(), normal.clone(), id);
    }

    boolean visible(Vector3f from, Vector3f to) {
        Vector3f delta = to.subtract(from);
        float distance = delta.length();
        if (distance <= 1.0e-6f) {
            return true;
        }
        Ray ray = new Ray(from, delta.divide(distance));
        ray.setLimit(distance);
        CollisionResults results = new CollisionResults();
        scene.collideWith(ray, results);
        for (int i = 0; i < results.size(); i++) {
            CollisionResult hit = results.getCollision(i);
            if (GiProbePlacement.participates(hit.getGeometry())
                    && hit.getDistance() < distance - 1.0e-6f) {
                return false;
            }
        }
        return true;
    }

    Vector3f[] probes(Vector3f low, Vector3f step) {
        Vector3f[] result = new Vector3f[125];
        for (int z = 0; z < 5; z++) {
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 5; x++) {
                    Vector3f origin = low.add(new Vector3f(x * step.x, y * step.y, z * step.z));
                    Vector3f moved = new Vector3f();
                    if (placement.place(scene, origin, step, clearance(step), moved)
                            && Vector3f.isValidVector(moved)
                            && Math.abs(moved.x - origin.x) <= step.x * 0.45f
                            && Math.abs(moved.y - origin.y) <= step.y * 0.45f
                            && Math.abs(moved.z - origin.z) <= step.z * 0.45f) {
                        result[x + 5 * (y + 5 * z)] = moved;
                    }
                }
            }
        }
        return result;
    }

    // Test the actual eight interpolation neighbors, after classification and
    // relocation. A box merely containing a target is not proof of coverage.
    boolean supports(Target target, Vector3f low, Vector3f step, Vector3f[] probes) {
        Vector3f q = target.position.subtract(low).divideLocal(step);
        if (q.x <= 0 || q.y <= 0 || q.z <= 0 || q.x >= 4 || q.y >= 4 || q.z >= 4) {
            return false;
        }
        int x = Math.min(3, (int) q.x);
        int y = Math.min(3, (int) q.y);
        int z = Math.min(3, (int) q.z);
        float support = 0;
        for (int corner = 0; corner < 8; corner++) {
            int dx = corner & 1;
            int dy = (corner >> 1) & 1;
            int dz = (corner >> 2) & 1;
            float weight = (dx == 0 ? 1 - (q.x - x) : q.x - x)
                    * (dy == 0 ? 1 - (q.y - y) : q.y - y)
                    * (dz == 0 ? 1 - (q.z - z) : q.z - z);
            Vector3f probe = probes[x + dx + 5 * (y + dy + 5 * (z + dz))];
            if (probe == null || weight <= 0.00001f) {
                continue;
            }
            Vector3f direction = probe.subtract(target.position);
            // Require support on the free-space side of this surface. Do not
            // count a fallback through a thin sheet as successfully covered.
            if (direction.dot(target.normal) < -clearance(step) || !visible(target.position, probe)) {
                continue;
            }
            support += weight;
        }
        return support >= 0.2f;
    }

    static Vector3f closest(Vector3f point, Vector3f a, Vector3f b, Vector3f c, Vector3f normal) {
        Vector3f q = point.subtract(normal.mult(point.subtract(a).dot(normal)));
        if (b.subtract(a).cross(q.subtract(a)).dot(normal) >= 0
                && c.subtract(b).cross(q.subtract(b)).dot(normal) >= 0
                && a.subtract(c).cross(q.subtract(c)).dot(normal) >= 0) {
            return q;
        }
        Vector3f best = segment(point, a, b);
        Vector3f other = segment(point, b, c);
        if (point.distanceSquared(other) < point.distanceSquared(best)) {
            best = other;
        }
        other = segment(point, c, a);
        return point.distanceSquared(other) < point.distanceSquared(best) ? other : best;
    }

    private static Vector3f segment(Vector3f p, Vector3f a, Vector3f b) {
        Vector3f edge = b.subtract(a);
        float t = p.subtract(a).dot(edge) / Math.max(edge.lengthSquared(), 1.0e-20f);
        return a.add(edge.mult(Math.max(0, Math.min(1, t))));
    }
}
