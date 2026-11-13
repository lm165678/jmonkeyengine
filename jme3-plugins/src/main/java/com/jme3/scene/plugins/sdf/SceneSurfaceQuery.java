package com.jme3.scene.plugins.sdf;

import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;

/**
 * Exact CPU triangle reference for future GPU/SDF scene queries. Uses jME's mesh
 * collision acceleration, not the approximate global-field preview. Transparent,
 * alpha-tested and deformed materials are explicitly unsupported blockers: they
 * are never silently skipped and reported as empty space. No GPU readback occurs.
 * Surface values are nearest covered baked texels, not evaluated live materials.
 * Call on the scene-owning thread after updateGeometricState(); validate bakes at
 * load time and invalidate them when geometry/material dependencies change.
 * Results borrow scene objects and are valid only until the next scene mutation.
 *
 * <pre>{@code
 * // 第一步：加载模型后验证烘焙数据，场景更新后创建参考查询器。
 * SceneSurfaceQuery query = new SceneSurfaceQuery();
 * rootNode.updateGeometricState();
 * // 第二步：查询世界空间射线。方向会归一化，范围单位是世界单位。
 * Hit hit = query.trace(rootNode, cam.getLocation(), cam.getDirection(), 100f);
 * // 第三步：MISS 只表示三角形范围内未命中；UNSUPPORTED 不能当作无障碍。
 * if (hit.status == HitStatus.HIT) {
 *     // 第四步：0.05 是允许的世界空间卡片深度误差，不是统一的全局推荐值。
 *     Surface surface = query.sample(hit, 0.05f);
 *     if (surface.status == SurfaceStatus.VALID) {
 *         Vector3f reflectance = surface.diffuse;
 *     }
 *     // MISSING_DATA / STALE_DATA / UNCOVERED 必须显式回退，不能当黑色有效材质。
 * }
 * // 第五步：刚体移动无需重建查询器；再次更新世界变换并 trace。
 * // 此路径无需 close，不拥有 GPU 资源；不用于万条射线的生产吞吐量。
 * }</pre>
 */
public final class SceneSurfaceQuery {
    public enum HitStatus {
        /** Exact geometric hit; shading attributes have a separate validity state. */
        HIT,
        /** No mesh intersection in the requested finite range. */
        MISS,
        /** A nearer alpha/transparent/deformed surface prevents a reliable answer. */
        UNSUPPORTED
    }

    public enum SurfaceStatus {
        /** Covered texel with matching capture depth. */
        VALID,
        /** Hit contains no complete saved surface attributes. */
        MISSING_DATA,
        /** Explicit invalidation or changed hierarchy. */
        STALE_DATA,
        /** No card covers this surface within the supplied world-space tolerance. */
        UNCOVERED,
        /** There was no supported geometric hit. */
        NO_HIT
    }

    /** Borrowed diagnostic result. Do not mutate its vectors or retain across scene changes. */
    public static final class Hit {
        public final HitStatus status;
        public final Geometry geometry;
        public final Spatial instance;
        public final int triangle;
        public final float distance;
        public final Vector3f position;
        public final Vector3f geometricNormal;
        public final boolean backFace;

        private Hit(HitStatus status, CollisionResult collision, Spatial instance, Vector3f direction) {
            this.status = status;
            this.instance = instance;
            geometry = collision == null ? null : collision.getGeometry();
            triangle = collision == null ? -1 : collision.getTriangleIndex();
            distance = collision == null ? Float.POSITIVE_INFINITY : collision.getDistance();
            position = collision == null ? null : collision.getContactPoint().clone();
            geometricNormal = collision == null ? null : collision.getContactNormal().normalize();
            backFace = geometricNormal != null && geometricNormal.dot(direction) > 0;
        }
    }

    /** Baked material values in linear space; normal is world-space inverse-transpose transformed. */
    public static final class Surface {
        public final SurfaceStatus status;
        public final int card;
        public final Vector3f diffuse;
        public final Vector3f normal;
        public final Vector3f emission;

        private Surface(SurfaceStatus status, int card, Vector3f diffuse, Vector3f normal, Vector3f emission) {
            this.status = status;
            this.card = card;
            this.diffuse = diffuse;
            this.normal = normal;
            this.emission = emission;
        }
    }

    /**
     * Traces finite world-space rays against the supplied subtree, independent of
     * camera CullHint. Uses exact mesh collision as the correctness fallback.
     * @param root scene to inspect
     * @param origin finite world origin
     * @param direction finite nonzero direction, normalized internally
     * @param maxDistance finite positive world distance
     * @return explicit geometric hit or miss/unsupported status
     */
    public Hit trace(Spatial root, Vector3f origin, Vector3f direction, float maxDistance) {
        if (root == null || !Vector3f.isValidVector(origin) || !Vector3f.isValidVector(direction)
                || direction.lengthSquared() < 1e-20f || !Float.isFinite(direction.lengthSquared())
                || !Float.isFinite(maxDistance) || maxDistance <= 0) {
            throw new IllegalArgumentException("Invalid finite ray");
        }
        Vector3f unit = direction.normalize();
        Ray ray = new Ray(origin, unit);
        ray.setLimit(maxDistance);
        CollisionResults collisions = new CollisionResults();
        root.collideWith(ray, collisions);
        CollisionResult nearest = collisions.getClosestCollision();
        if (nearest == null || nearest.getDistance() > maxDistance) {
            return new Hit(HitStatus.MISS, null, null, unit);
        }
        Geometry geometry = nearest.getGeometry();
        Spatial owner = geometry;
        for (Spatial cursor = geometry; cursor != null; cursor = cursor.getParent()) {
            if (cursor.getUserData(SurfaceBakeData.USER_DATA_KEY) != null) {
                owner = cursor;
            }
            if (cursor == root) {
                break;
            }
        }
        boolean unsupported = geometry.getMaterial() != null
                && (geometry.getMaterial().isTransparent()
                || geometry.getMaterial().getParam("AlphaDiscardThreshold") != null
                || geometry.getMaterial().getParam("NumberOfBones") != null
                || geometry.getMaterial().getParam("NumberOfMorphTargets") != null);
        return new Hit(unsupported ? HitStatus.UNSUPPORTED : HitStatus.HIT, nearest, owner, unit);
    }

    /**
     * Resolves the exact hit Geometry to its six cards and checks captured depth.
     * Does not require GPU atlas residency; this is the CPU reference over saved pixels.
     * @param hit result from trace, before scene mutation
     * @param worldTolerance finite nonnegative depth error along the capture direction
     * @return validity plus attributes, never invented values for missing coverage
     */
    public Surface sample(Hit hit, float worldTolerance) {
        if (!Float.isFinite(worldTolerance) || worldTolerance < 0) {
            throw new IllegalArgumentException("Invalid world tolerance");
        }
        if (hit == null || hit.status != HitStatus.HIT) {
            return invalid(SurfaceStatus.NO_HIT);
        }
        SurfaceBakeData data = hit.instance.getUserData(SurfaceBakeData.USER_DATA_KEY);
        if (data == null) {
            return invalid(SurfaceStatus.MISSING_DATA);
        }
        if (data.isInvalidated()) {
            return invalid(SurfaceStatus.STALE_DATA);
        }
        SurfaceCacheLayout layout = data.getLayout();
        int geometryIndex = -1;
        try {
            for (int i = 0; i < layout.getCardCount() / 6; i++) {
                if (layout.resolve(hit.instance, i) == hit.geometry) {
                    geometryIndex = i;
                    break;
                }
            }
        } catch (IllegalArgumentException failure) {
            return invalid(SurfaceStatus.STALE_DATA);
        }
        if (geometryIndex < 0) {
            return invalid(SurfaceStatus.STALE_DATA);
        }
        SurfaceAttributeData pixels = data.getAttributes();
        int size = pixels.getResolution();
        Vector3f localHit = hit.geometry.worldToLocal(hit.position, null);
        float best = Float.POSITIVE_INFINITY;
        int selected = -1;
        int selectedPixel = -1;
        for (int card = geometryIndex * 6; card < geometryIndex * 6 + 6; card++) {
            Camera camera = layout.createCamera(card, size);
            Vector3f projected = camera.getScreenCoordinates(localHit);
            if (projected.x < 0 || projected.y < 0 || projected.x >= size || projected.y >= size
                    || projected.z < 0 || projected.z > 1) {
                continue;
            }
            int pixel = (int) projected.x + size * (int) projected.y;
            float depth = pixels.component(card, 12 * size * size + pixel);
            if (depth >= 1 || pixels.component(card, pixel * 4 + 3) <= 0) {
                continue;
            }
            Vector3f captured = camera.getWorldCoordinates(
                    new com.jme3.math.Vector2f(projected.x, projected.y), depth);
            hit.geometry.localToWorld(captured, captured);
            float error = captured.distance(hit.position);
            if (error <= worldTolerance && error < best) {
                best = error;
                selected = card;
                selectedPixel = pixel;
            }
        }
        if (selected < 0) {
            return invalid(SurfaceStatus.UNCOVERED);
        }
        int count = size * size;
        Vector3f diffuse = read(pixels, selected, selectedPixel * 4);
        Vector3f normal = read(pixels, selected, count * 4 + selectedPixel * 4);
        Vector3f emission = read(pixels, selected, count * 8 + selectedPixel * 4);
        normal = hit.geometry.getWorldTransform().toTransformMatrix().toRotationMatrix()
                .invert().transpose().mult(normal).normalizeLocal();
        if (!Vector3f.isValidVector(normal) || normal.lengthSquared() < .5f) {
            return invalid(SurfaceStatus.UNCOVERED);
        }
        return new Surface(SurfaceStatus.VALID, selected, diffuse, normal, emission);
    }

    private Vector3f read(SurfaceAttributeData data, int card, int offset) {
        return new Vector3f(data.component(card, offset), data.component(card, offset + 1), data.component(card, offset + 2));
    }

    private Surface invalid(SurfaceStatus status) {
        return new Surface(status, -1, null, null, null);
    }
}
