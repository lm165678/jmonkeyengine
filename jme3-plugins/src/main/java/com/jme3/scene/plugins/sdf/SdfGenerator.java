package com.jme3.scene.plugins.sdf;

import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Headless CPU triangle-mesh distance-field baker. Uses BVH-pruned exact nearest
 * triangle queries; sign uses deterministic majority parity rays. Grid samples are
 * exact mesh distances up to floating point error; interpolation is approximate.
 * No GL context, renderer, collision-tree mutation or scene controls are required.
 * Geometry topology is not repaired or checked for watertightness. See SignMode.
 */
public final class SdfGenerator {
    private SdfGenerator() {
    }

    /**
     * Convenience capture+bake on the calling thread. Does not attach results or save files.
     * <pre>{@code
     * // 第一步：加载不再被其他线程修改的离线模型。
     * Spatial model = assetManager.loadModel("Models/MyModel.j3o");
     * // 第二步：闭合模型生成带符号距离，开放模型选择 UNSIGNED。
     * SdfSettings settings = new SdfSettings(64, 64, 64, .1f, SdfSettings.SignMode.EVEN_ODD);
     * SdfVolume sdf = SdfGenerator.generate(model, settings, fraction -> true);
     * // 第三步：成功后附加并另存，原始资产不自动覆盖。
     * sdf.attachTo(model);
     * BinaryExporter.getInstance().save(model, new File("MyModel-sdf.j3o"));
     * }</pre>
     * @param model stable model root
     * @param settings immutable bake configuration
     * @param progress optional callback, null disables progress reporting
     * @return generated root-local distance volume
     */
    public static SdfVolume generate(Spatial model, SdfSettings settings, SdfProgressListener progress) {
        Objects.requireNonNull(settings);
        report(progress, 0);
        return generate(SdfMeshSnapshot.capture(model), settings, progress);
    }

    /**
     * Bakes an immutable snapshot, safe to run on a worker thread. The callback runs
     * on that worker and can return false to cancel; no partial result is published.
     * <pre>{@code
     * // 第一步（场景线程）：固定当前 CPU 网格与子节点变换。
     * SdfMeshSnapshot snapshot = SdfMeshSnapshot.capture(model);
     * // 第二步（后台线程）：计算；SDK 可在此接入进度条和取消按钮。
     * Future<SdfVolume> task = executor.submit(() ->
     *         SdfGenerator.generate(snapshot, settings, fraction -> !cancelRequested.get()));
     * // 第三步：成功后回到场景线程调用 result.attachTo(model)，再执行用户选择的保存。
     * // 若模型在后台计算期间已修改，编辑器应丢弃旧结果或提示重新生成。
     * }</pre>
     * @param snapshot immutable geometry snapshot
     * @param settings immutable bake configuration
     * @param progress optional progress/cancellation callback
     * @return completed volume; throws CancellationException on cancellation/interruption
     */
    public static SdfVolume generate(SdfMeshSnapshot snapshot, SdfSettings settings, SdfProgressListener progress) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(settings);
        report(progress, 0);
        SdfBvh bvh = new SdfBvh(snapshot);
        double longest = 0;
        for (int axis = 0; axis < 3; axis++) {
            longest = Math.max(longest, bvh.root.max[axis] - bvh.root.min[axis]);
        }
        double padding = longest * settings.padding;
        Vector3f min = new Vector3f();
        Vector3f max = new Vector3f();
        for (int axis = 0; axis < 3; axis++) {
            float low = (float) (bvh.root.min[axis] - padding);
            float high = (float) (bvh.root.max[axis] + padding);
            if (!Float.isFinite(low) || !Float.isFinite(high) || low >= high) {
                throw new IllegalArgumentException("Model bounds cannot be represented as a float SDF grid");
            }
            min.set(axis, low);
            max.set(axis, high);
        }
        report(progress, .05f);
        float[] values = new float[SdfSettings.sampleCount(settings.x, settings.y, settings.z)];
        double[] p = new double[3];
        double epsilon = longest * 1e-9;
        SdfBvh.Hits hits = new SdfBvh.Hits();
        for (int z = 0; z < settings.z; z++) {
            p[2] = min.z + ((double) max.z - min.z) * z / (settings.z - 1);
            for (int y = 0; y < settings.y; y++) {
                SdfMeshSnapshot.cancelled();
                p[1] = min.y + ((double) max.y - min.y) * y / (settings.y - 1);
                for (int x = 0; x < settings.x; x++) {
                    p[0] = min.x + ((double) max.x - min.x) * x / (settings.x - 1);
                    double distance = Math.sqrt(bvh.distanceSquared(p));
                    if (distance <= epsilon) {
                        distance = 0;
                    } else if (settings.signMode == SdfSettings.SignMode.EVEN_ODD && bvh.inside(p, hits, epsilon)) {
                        distance = -distance;
                    }
                    if (!Float.isFinite((float) distance)) {
                        throw new IllegalArgumentException("Distance exceeds float range");
                    }
                    values[x + settings.x * (y + settings.y * z)] = (float) distance;
                }
            }
            report(progress, .05f + .95f * (z + 1) / settings.z);
        }
        SdfVolume result = new SdfVolume(settings, min, max, values);
        result.setSourceGeometryHash(snapshot.geometryHash());
        return result;
    }

    private static void report(SdfProgressListener progress, float fraction) {
        SdfMeshSnapshot.cancelled();
        if (progress != null && !progress.onProgress(fraction)) {
            throw new CancellationException("SDF generation cancelled");
        }
    }

    /**
     * SDK bake entry point, equivalent to generate(). Generates ONLY the distance
     * field, with no surface layout, material capture or graphics-context requirement.
     * <pre>{@code
     * // 只需要碰撞/距离查询时只调用这个入口。
     * SdfVolume sdf = SdfGenerator.bake(model, settings, progress);
     * sdf.attachTo(model);
     * // 可选且独立：SurfaceAttributeBaker.bake(...)；不需要 GI 时完全不用调用。
     * }</pre>
     * @param model static source root
     * @param settings distance grid settings
     * @param progress optional progress/cancellation listener
     * @return SDF only, unattached
     */
    public static SdfVolume bake(Spatial model, SdfSettings settings, SdfProgressListener progress) {
        return generate(model, settings, progress);
    }
}
