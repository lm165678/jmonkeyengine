package com.jme3.scene.plugins.sdf;

import com.jme3.export.*;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializable six-direction orthographic capture layout per triangle geometry.
 * Cards capture the nearest surface only; concave/hidden surfaces may have no valid
 * coverage. A caller MUST validate captured depth before using a card at a hit.
 * This is a bounded baseline layout, not an automatic complete surface unwrap.
 * Geometry indices refer to depth-first child-index paths, not non-unique names.
 *
 * <pre>{@code
 * // 第一步：SDK 烘焙布局；不需要 OpenGL，不分配纹理，不改变模型。
 * SurfaceCacheLayout layout = SurfaceCacheLayout.bake(model);
 * // 第二步：布局可由 SdfBakeData 一起保存；加载后无需重新规划捕获方向。
 * // 每个 Geometry 六个方向，cardIndex = geometryIndex * 6 + face。
 * Camera capture = layout.createCamera(0, 128);
 * // 第三步：命中点先从模型根局部空间变换到对应 Geometry 局部空间。
 * Vector3f localHit = layout.getGeometryToRoot(0).invert().mult(rootLocalHit);
 * // 第四步：投影到卡片，用 UV 查询属性，必须比较捕获深度；遮住的表面不能复用前面的颜色。
 * Vector3f pixel = capture.getScreenCoordinates(localHit);
 * // UV = pixel.xy / 128，depth = pixel.z；越界或深度不匹配返回缺失覆盖。
 * }</pre>
 */
public final class SurfaceCacheLayout implements Savable {
    private String[] paths;
    private Vector3f[] minimum;
    private Vector3f[] maximum;
    private Matrix4f[] geometryToRoot;

    /** Deserialization only. Use bake to produce a valid layout. */
    public SurfaceCacheLayout() {
    }

    /**
     * Builds on the scene-owning thread. Static CPU meshes only; rebake after
     * geometry, hierarchy or descendant transforms change. Root placement is excluded.
     * @param root model root
     * @return new immutable-by-convention layout
     */
    public static SurfaceCacheLayout bake(Spatial root) {
        List<String> paths = new ArrayList<>();
        List<Geometry> geometries = new ArrayList<>();
        List<Matrix4f> transforms = new ArrayList<>();
        collect(root, "", new Matrix4f(), paths, geometries, transforms);
        if (geometries.isEmpty()) {
            throw new IllegalArgumentException("No triangle geometries");
        }
        SurfaceCacheLayout result = new SurfaceCacheLayout();
        result.paths = paths.toArray(new String[0]);
        result.geometryToRoot = transforms.toArray(new Matrix4f[0]);
        result.minimum = new Vector3f[geometries.size()];
        result.maximum = new Vector3f[geometries.size()];
        for (int i = 0; i < geometries.size(); i++) {
            SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(geometries.get(i));
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
            for (double[] triangle : snapshot.triangles) {
                for (int vertex = 0; vertex < 3; vertex++) {
                    Vector3f point = new Vector3f((float) triangle[vertex * 3], (float) triangle[vertex * 3 + 1],
                            (float) triangle[vertex * 3 + 2]);
                    min.minLocal(point);
                    max.maxLocal(point);
                }
            }
            float padding = Math.max(max.subtract(min).length() * .01f, 1e-5f);
            result.minimum[i] = min.subtract(padding, padding, padding);
            result.maximum[i] = max.add(padding, padding, padding);
        }
        return result;
    }

    private static void collect(Spatial spatial, String path, Matrix4f transform,
            List<String> paths, List<Geometry> geometries, List<Matrix4f> transforms) {
        SdfMeshSnapshot.cancelled();
        if (spatial instanceof Geometry) {
            Geometry geometry = (Geometry) spatial;
            Mesh mesh = geometry.getMesh();
            if (mesh != null && mesh.getTriangleCount() > 0 && (mesh.getMode() == Mesh.Mode.Triangles
                    || mesh.getMode() == Mesh.Mode.TriangleStrip || mesh.getMode() == Mesh.Mode.TriangleFan)) {
                paths.add(path);
                geometries.add(geometry);
                transforms.add(transform);
            }
        } else if (spatial instanceof Node) {
            Node node = (Node) spatial;
            for (int i = 0; i < node.getQuantity(); i++) {
                Spatial child = node.getChild(i);
                collect(child, path + "/" + i, transform.mult(child.getLocalTransform().toTransformMatrix()),
                        paths, geometries, transforms);
            }
        }
    }

    /** @return number of geometries */
    public int getGeometryCount() {
        return paths.length;
    }

    /** @return six cards per geometry; no textures are allocated by this method */
    public int getCardCount() {
        return paths.length * 6;
    }

    String getPath(int geometry) {
        return paths[geometry];
    }

    /** @param geometry index @return independent matrix into model-root local space */
    public Matrix4f getGeometryToRoot(int geometry) {
        return geometryToRoot[geometry].clone();
    }

    /**
     * CPU broad phase for SDK hit inspection. Returns all geometry bounds near a
     * root-local point, NOT an exact nearest triangle or material assignment.
     * Candidate cards still require depth/coverage validation. O(geometry count),
     * not a GPU ray-query acceleration structure.
     * <pre>{@code
     * // 第一步：SDF 命中只给出根局部位置，不包含 materialId。
     * int[] candidates = layout.findCandidates(rootLocalHit, localTolerance);
     * // 第二步：对候选 Geometry 的卡片投影，检查深度；全部失败则标为缺失覆盖。
     * // 不允许直接把 candidates[0] 的材质当作命中材质。
     * }</pre>
     * @param point finite point in model-root local units
     * @param tolerance nonnegative root-local bounds tolerance
     * @return geometry indices whose root-local AABB includes the tolerance region
     */
    public int[] findCandidates(Vector3f point, float tolerance) {
        if (point == null || !Float.isFinite(point.x) || !Float.isFinite(point.y) || !Float.isFinite(point.z)
                || !Float.isFinite(tolerance) || tolerance < 0) {
            throw new IllegalArgumentException("Invalid candidate query");
        }
        int[] candidates = new int[paths.length];
        int count = 0;
        for (int geometry = 0; geometry < paths.length; geometry++) {
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
            for (int corner = 0; corner < 8; corner++) {
                Vector3f p = new Vector3f((corner & 1) == 0 ? minimum[geometry].x : maximum[geometry].x,
                        (corner & 2) == 0 ? minimum[geometry].y : maximum[geometry].y,
                        (corner & 4) == 0 ? minimum[geometry].z : maximum[geometry].z);
                geometryToRoot[geometry].mult(p, p);
                min.minLocal(p);
                max.maxLocal(p);
            }
            if (point.x >= min.x - tolerance && point.x <= max.x + tolerance
                    && point.y >= min.y - tolerance && point.y <= max.y + tolerance
                    && point.z >= min.z - tolerance && point.z <= max.z + tolerance) {
                candidates[count++] = geometry;
            }
        }
        return java.util.Arrays.copyOf(candidates, count);
    }

    /**
     * Projects a root-local hit into a card, without asserting visibility.
     * <pre>{@code
     * // x/y 为 [0,1] UV，z 为 [0,1] 深度；越界说明此卡片不能覆盖该点。
     * Vector3f uvDepth = layout.project(cardIndex, rootLocalHit);
     * // GPU 使用 card.getLocalToClip() * inverse(layout.getGeometryToRoot(cardIndex / 6))
     * // 可得到同样的 root-local -> clip 矩阵，再调用 projectSurfaceCard。
     * }</pre>
     * @param card card index
     * @param rootLocalPoint model-root local hit position
     * @return UV and depth; caller must check bounds and captured depth
     */
    public Vector3f project(int card, Vector3f rootLocalPoint) {
        Camera camera = createCamera(card, 1);
        Vector3f local = geometryToRoot[card / 6].invert().mult(rootLocalPoint);
        return camera.getScreenCoordinates(local);
    }

    /**
     * Resolves a saved path. Validate the SdfBakeData before binding a changed model.
     * @param root same model hierarchy
     * @param geometry index
     * @return live geometry; no clone or mutation
     */
    public Geometry resolve(Spatial root, int geometry) {
        Spatial found = root;
        for (String part : paths[geometry].split("/")) {
            if (!part.isEmpty()) {
                if (!(found instanceof Node)) {
                    throw new IllegalArgumentException("Stale surface layout hierarchy");
                }
                found = ((Node) found).getChild(Integer.parseInt(part));
            }
        }
        if (!(found instanceof Geometry)) {
            throw new IllegalArgumentException("Stale surface layout geometry");
        }
        return (Geometry) found;
    }

    /**
     * Returns an independent camera in geometry-local space. Render a transform-free
     * geometry proxy, not the world-transformed source. Faces: +X,-X,+Y,-Y,+Z,-Z.
     * @param card index
     * @param resolution square image width/height, positive
     * @return orthographic capture camera
     */
    public Camera createCamera(int card, int resolution) {
        if (card < 0 || card >= getCardCount() || resolution < 1) {
            throw new IllegalArgumentException("Invalid surface card or resolution");
        }
        int geometry = card / 6;
        int axis = (card % 6) / 2;
        float sign = card % 2 == 0 ? 1 : -1;
        Vector3f center = minimum[geometry].add(maximum[geometry]).multLocal(.5f);
        Vector3f extent = maximum[geometry].subtract(minimum[geometry]).multLocal(.5f);
        Vector3f direction = new Vector3f();
        direction.set(axis, sign);
        float distance = extent.get(axis) * 2;
        Camera camera = new Camera(resolution, resolution);
        camera.setParallelProjection(true);
        camera.setLocation(center.add(direction.mult(distance)));
        camera.lookAt(center, axis == 1 ? Vector3f.UNIT_Z : Vector3f.UNIT_Y);
        Vector3f left = camera.getLeft();
        Vector3f up = camera.getUp();
        float halfWidth = Math.abs(left.x) * extent.x + Math.abs(left.y) * extent.y + Math.abs(left.z) * extent.z;
        float halfHeight = Math.abs(up.x) * extent.x + Math.abs(up.y) * extent.y + Math.abs(up.z) * extent.z;
        camera.setFrustum(distance - extent.get(axis), distance + extent.get(axis),
                -halfWidth, halfWidth, halfHeight, -halfHeight);
        camera.update();
        return camera;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule c = exporter.getCapsule(this);
        c.write(1, "version", 0);
        c.write(paths, "paths", null);
        c.write(minimum, "minimum", null);
        c.write(maximum, "maximum", null);
        c.write(geometryToRoot, "geometryToRoot", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule c = importer.getCapsule(this);
        if (c.readInt("version", 0) != 1) {
            throw new IOException("Unsupported surface layout version");
        }
        paths = c.readStringArray("paths", null);
        Savable[] mins = c.readSavableArray("minimum", null);
        Savable[] maxs = c.readSavableArray("maximum", null);
        Savable[] matrices = c.readSavableArray("geometryToRoot", null);
        if (paths == null || paths.length == 0 || mins == null || maxs == null || matrices == null
                || mins.length != paths.length || maxs.length != paths.length || matrices.length != paths.length) {
            throw new IOException("Invalid surface layout arrays");
        }
        minimum = new Vector3f[paths.length];
        maximum = new Vector3f[paths.length];
        geometryToRoot = new Matrix4f[paths.length];
        for (int i = 0; i < paths.length; i++) {
            if (!(mins[i] instanceof Vector3f) || !(maxs[i] instanceof Vector3f) || !(matrices[i] instanceof Matrix4f)
                    || paths[i] == null || !paths[i].matches("(/[0-9]+)*")) {
                throw new IOException("Invalid surface layout entry");
            }
            minimum[i] = (Vector3f) mins[i];
            maximum[i] = (Vector3f) maxs[i];
            geometryToRoot[i] = (Matrix4f) matrices[i];
            for (int axis = 0; axis < 3; axis++) {
                if (!Float.isFinite(minimum[i].get(axis)) || !Float.isFinite(maximum[i].get(axis))
                        || minimum[i].get(axis) >= maximum[i].get(axis)) {
                    throw new IOException("Invalid surface bounds");
                }
            }
        }
    }
}
