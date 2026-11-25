package com.jme3.material.skin;

import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Offline UV0 thickness and smooth mesh-curvature baking. The input must be an
 * exclusively owned, undeformed model snapshot, never a live scene being edited.
 * Rays measure the first outward-facing exit along the inward vertex normal.
 * Open surfaces or exits beyond the range produce maximum (opaque) thickness.
 * This is a normal-thickness approximation, not light-direction thickness or a
 * general BSSRDF. Overlapping UVs conservatively retain the thicker sample.
 *
 * <pre>{@code
 * // 第一步：在工作线程加载独立模型，按实际尺寸设置毫米换算。
 * Spatial snapshot = assetManager.loadModel("Models/head.j3o");
 * // 第二步：按模型实际身高计算换算值；例如 8 单位高对应 400 毫米，则填 50。
 * SkinSurfaceBaker.Result maps = SkinSurfaceBaker.bake(snapshot, null, 512, 50f, 30f);
 * // 第三步：将 thickness / 30 和 curvature / SkinMaxCurvature 保存为线性纹理。
 * // 在渲染线程绑定 SkinThicknessMap、SkinCurvatureMap，并设 SkinThickness=30。
 * // 开启 SkinTransmission；关闭此选项即可恢复原有曲率 LUT 路径。
 * }</pre>
 * Requires UV0 and vertex normals. Geometry without either is skipped. Use
 * distinct UV charts for unrelated surfaces; no unwrap or animation baking is
 * performed. Results contain unpadded scalar data and a coverage mask; an asset
 * exporter must pad its UV islands before filtering. Interrupt cancels the bake.
 */
public final class SkinSurfaceBaker {
    private SkinSurfaceBaker() {
    }

    /** Linear values: millimeters, inverse millimeters, and covered texels. */
    public static final class Result {
        public final int size;
        public final float[] thickness;
        public final float[] curvature;
        public final boolean[] covered;
        public int missingExits;
        public int overlappingTexels;

        private Result(int size, float maximumThickness) {
            this.size = size;
            thickness = new float[size * size];
            curvature = new float[size * size];
            covered = new boolean[size * size];
            java.util.Arrays.fill(thickness, maximumThickness);
        }
    }

    /**
     * @param model isolated collision model, updated here before baking
     * @param materialKey optional asset name selecting receiver geometries
     * @param size square UV0 output size, 16..2048
     * @param millimetersPerUnit physical scale of the snapshot's world units
     * @param maximumThickness ray range and opaque value, in millimeters
     * @return unpadded scalar maps; the input's mesh buffers remain unchanged
     */
    public static Result bake(Spatial model, String materialKey, int size,
            float millimetersPerUnit, float maximumThickness) {
        if (size < 16 || size > 2048 || !Float.isFinite(millimetersPerUnit)
                || millimetersPerUnit <= 0 || !Float.isFinite(maximumThickness) || maximumThickness <= 0) {
            throw new IllegalArgumentException("Invalid size, physical scale or thickness range");
        }
        model.updateGeometricState();
        List<Geometry> receivers = new ArrayList<>();
        model.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof Geometry)) {
                return;
            }
            Geometry geometry = (Geometry) spatial;
            if (geometry.getMaterial() != null
                    && (materialKey == null || materialKey.equals(geometry.getMaterial().getAssetName()))
                    && geometry.getMesh().getBuffer(Type.TexCoord) != null
                    && geometry.getMesh().getBuffer(Type.Normal) != null) {
                receivers.add(geometry);
            }
        });
        if (receivers.isEmpty()) {
            throw new IllegalArgumentException("No UV0 receiver uses the selected material in this model");
        }
        Result result = new Result(size, maximumThickness);
        for (Geometry geometry : receivers) {
            bakeGeometry(model, geometry, result, millimetersPerUnit, maximumThickness);
        }
        return result;
    }

    private static void bakeGeometry(Spatial collision, Geometry geometry, Result result, float units, float maximum) {
        var mesh = geometry.getMesh();
        int count = mesh.getVertexCount();
        Vector3f[] positions = new Vector3f[count];
        Vector3f[] normals = new Vector3f[count];
        FloatBuffer normalData = mesh.getFloatBuffer(Type.Normal);
        FloatBuffer positionData = mesh.getFloatBuffer(Type.Position);
        FloatBuffer uv = mesh.getFloatBuffer(Type.TexCoord);
        Map<List<Float>, Integer> welds = new HashMap<>();
        int[] welded = new int[count];
        for (int i = 0; i < count; i++) {
            Vector3f p = new Vector3f(positionData.get(i * 3), positionData.get(i * 3 + 1), positionData.get(i * 3 + 2));
            Vector3f n = new Vector3f(normalData.get(i * 3), normalData.get(i * 3 + 1), normalData.get(i * 3 + 2));
            positions[i] = geometry.localToWorld(p, new Vector3f());
            normals[i] = geometry.getWorldRotation().mult(n.divide(geometry.getWorldScale())).normalizeLocal();
            var key = java.util.Arrays.asList(p.x + 0f, p.y + 0f, p.z + 0f, n.x + 0f, n.y + 0f, n.z + 0f);
            welded[i] = welds.computeIfAbsent(key, ignored -> welds.size());
        }
        float[] curvature = new float[welds.size()];
        float[] weights = new float[curvature.length];
        List<Map<Integer, Float>> neighbors = new ArrayList<>();
        for (int i = 0; i < curvature.length; i++) {
            neighbors.add(new HashMap<>());
        }
        var indices = mesh.getIndicesAsList();
        for (int i = 0; i < indices.size(); i += 3) {
            for (int j = 0; j < 3; j++) {
                int a = indices.get(i + j);
                int b = indices.get(i + (j + 1) % 3);
                Vector3f edge = positions[a].subtract(positions[b]);
                float length = edge.length();
                if (length < 1e-7f) {
                    continue;
                }
                float value = Math.max(0, normals[a].subtract(normals[b]).dot(edge) / (length * length)) / units;
                curvature[welded[a]] += value * length;
                curvature[welded[b]] += value * length;
                weights[welded[a]] += length;
                weights[welded[b]] += length;
                if (normals[a].dot(normals[b]) > .5f) {
                    neighbors.get(welded[a]).put(welded[b], length);
                    neighbors.get(welded[b]).put(welded[a], length);
                }
            }
        }
        for (int i = 0; i < curvature.length; i++) {
            curvature[i] /= Math.max(weights[i], 1e-12f);
        }
        // Smooth vertex estimates, not per-triangle derivatives. UV duplicates
        // share the estimate, avoiding chart and triangle-shaped discontinuities.
        for (int pass = 0; pass < 2; pass++) {
            float[] next = curvature.clone();
            for (int i = 0; i < curvature.length; i++) {
                float sum = 0;
                float weight = 0;
                for (var neighbor : neighbors.get(i).entrySet()) {
                    sum += curvature[neighbor.getKey()] * neighbor.getValue();
                    weight += neighbor.getValue();
                }
                if (weight > 0) {
                    next[i] = .5f * curvature[i] + .5f * sum / weight;
                }
            }
            curvature = next;
        }
        for (int triangle = 0; triangle < indices.size(); triangle += 3) {
            cancelled();
            int a = indices.get(triangle);
            int b = indices.get(triangle + 1);
            int c = indices.get(triangle + 2);
            Vector2f ua = new Vector2f(uv.get(a * 2) * result.size, uv.get(a * 2 + 1) * result.size);
            Vector2f ub = new Vector2f(uv.get(b * 2) * result.size, uv.get(b * 2 + 1) * result.size);
            Vector2f uc = new Vector2f(uv.get(c * 2) * result.size, uv.get(c * 2 + 1) * result.size);
            float area = cross(ub.subtract(ua), uc.subtract(ua));
            if (Math.abs(area) < 1e-8f) {
                continue;
            }
            int x0 = Math.max(0, (int) Math.floor(Math.min(ua.x, Math.min(ub.x, uc.x))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ua.y, Math.min(ub.y, uc.y))));
            int x1 = Math.min(result.size - 1, (int) Math.ceil(Math.max(ua.x, Math.max(ub.x, uc.x))));
            int y1 = Math.min(result.size - 1, (int) Math.ceil(Math.max(ua.y, Math.max(ub.y, uc.y))));
            for (int y = y0; y <= y1; y++) {
                cancelled();
                for (int x = x0; x <= x1; x++) {
                    Vector2f point = new Vector2f(x + .5f, y + .5f);
                    float wa = cross(ub.subtract(point), uc.subtract(point)) / area;
                    float wb = cross(uc.subtract(point), ua.subtract(point)) / area;
                    float wc = 1 - wa - wb;
                    if (Math.min(wa, Math.min(wb, wc)) < -1e-5f) {
                        continue;
                    }
                    Vector3f position = positions[a].mult(wa).addLocal(positions[b].mult(wb)).addLocal(positions[c].mult(wc));
                    Vector3f normal = normals[a].mult(wa).addLocal(normals[b].mult(wb)).addLocal(normals[c].mult(wc)).normalizeLocal();
                    float epsilon = Math.max(1e-5f / units, maximumEpsilon(maximum, units));
                    Ray ray = new Ray(position.subtract(normal.mult(epsilon)), normal.negate());
                    ray.setLimit(maximum / units);
                    CollisionResults hits = new CollisionResults();
                    collision.collideWith(ray, hits);
                    float distance = maximum;
                    boolean exit = false;
                    for (var hit : hits) {
                        if (hit.getDistance() > epsilon && hit.getDistance() < ray.getLimit()
                                && hit.getContactNormal().dot(ray.getDirection()) > .01f) {
                            distance = Math.min(maximum, (hit.getDistance() + epsilon) * units);
                            exit = true;
                            break;
                        }
                    }
                    if (!exit) {
                        result.missingExits++;
                    }
                    int pixel = y * result.size + x;
                    float value = curvature[welded[a]] * wa + curvature[welded[b]] * wb + curvature[welded[c]] * wc;
                    if (result.covered[pixel]) {
                        result.overlappingTexels++;
                        distance = Math.max(distance, result.thickness[pixel]);
                        value = .5f * (value + result.curvature[pixel]);
                    }
                    result.covered[pixel] = true;
                    result.thickness[pixel] = distance;
                    result.curvature[pixel] = value;
                }
            }
        }
    }

    private static float maximumEpsilon(float maximum, float units) {
        return Math.min(.001f, maximum * 1e-4f) / units;
    }

    private static float cross(Vector2f a, Vector2f b) {
        return a.x * b.y - a.y * b.x;
    }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Skin surface bake cancelled");
        }
    }
}
