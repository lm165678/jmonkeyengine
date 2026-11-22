package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import java.util.function.BooleanSupplier;

/**
 * Renderer-independent, streaming SH9 projection for diffuse probe irradiance.
 * This low-level extension accepts linear HDR incident radiance from a capture
 * backend. It does not itself render a scene or compute visibility moments.
 * Coefficients use real SH order 00, 1-1, 10, 11, 2-2, 2-1, 20, 21, 22,
 * with cosine convolution included (irradiance, not radiance or irradiance/pi).
 * RGB coefficients are interleaved, requiring 27 floats per probe.
 *
 * <pre>{@code
 * // 第一步：创建分块网格，避免为整个世界分配烘焙内存。
 * GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, new Vector3f(2, 2, 2), 3, 3, 3);
 * // 第二步：提供线性 HDR 入射辐射；这里用均匀环境验证积分。
 * GiProbeBaker.RadianceSource sky = (position, direction, result) -> result.set(1, 1, 1);
 * // 第三步：逐探针写入调用方的资源存储；可取消，未完成结果不要发布。
 * float[][] coefficients = new float[grid.getProbeCount()][];
 * GiProbeBaker.bake(grid, 1024, sky,
 *         (index, sh) -> coefficients[index] = sh, () -> false);
 * }</pre>
 * There is no implicit quality default. Use at least 32 samples; 1024 is a useful
 * starting point. Work runs synchronously on the calling thread. CPU snapshots
 * may be processed on a worker; a GPU source must obey its renderer's thread and
 * lifecycle rules. The sink receives an owned coefficient array per probe and
 * should stream it to chunk storage. Cancellation throws before the next probe
 * or sample; callers must discard or explicitly resume partially written chunks.
 */
public final class GiProbeBaker {
    private GiProbeBaker() {
    }

    /** Callback must write finite, nonnegative, linear RGB radiance to result. */
    @FunctionalInterface
    public interface RadianceSource {
        /** Input vectors are borrowed and must not be mutated or retained. */
        void sample(Vector3f position, Vector3f direction, Vector3f result);
    }

    /** Streaming output; coefficient storage belongs to the sink after the call. */
    @FunctionalInterface
    public interface ProbeSink {
        void accept(int probeIndex, float[] irradianceSh);
    }

    /** Projects deterministic equal-area spherical samples, one probe at a time. */
    public static void bake(GiProbeGrid grid, int samples, RadianceSource source,
            ProbeSink sink, BooleanSupplier cancelled) {
        java.util.Objects.requireNonNull(grid, "grid");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(sink, "sink");
        java.util.Objects.requireNonNull(cancelled, "cancelled");
        if (samples < 32) {
            throw new IllegalArgumentException("At least 32 spherical samples required");
        }
        Vector3f position = new Vector3f();
        for (int probe = 0; probe < grid.getProbeCount(); probe++) {
            grid.position(probe, position);
            sink.accept(probe, project(position, samples, source, cancelled));
        }
    }

    /** Projects one captured probe. Worker threads must receive a CPU copy of the radiance. */
    public static float[] project(Vector3f position, int samples, RadianceSource source, BooleanSupplier cancelled) {
        if (samples < 32 || !Vector3f.isValidVector(position)) {
            throw new IllegalArgumentException("Valid position and at least 32 samples required");
        }
        Vector3f direction = new Vector3f();
        Vector3f radiance = new Vector3f();
        double[] basis = new double[9];
        double[] sums = new double[27];
        for (int sample = 0; sample < samples; sample++) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("GI bake cancelled");
            }
            double z = 1.0 - 2.0 * (sample + 0.5) / samples;
            double radius = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double phi = sample * Math.PI * (3.0 - Math.sqrt(5.0));
            direction.set((float) (radius * Math.cos(phi)),
                    (float) (radius * Math.sin(phi)), (float) z);
            radiance.set(Float.NaN, Float.NaN, Float.NaN);
            source.sample(position, direction, radiance);
            if (!Vector3f.isValidVector(radiance)
                    || radiance.x < 0 || radiance.y < 0 || radiance.z < 0) {
                throw new IllegalArgumentException("Capture must return finite nonnegative radiance");
            }
            basis(direction, basis);
            for (int coefficient = 0; coefficient < 9; coefficient++) {
                sums[coefficient * 3] += basis[coefficient] * radiance.x;
                sums[coefficient * 3 + 1] += basis[coefficient] * radiance.y;
                sums[coefficient * 3 + 2] += basis[coefficient] * radiance.z;
            }
        }
        float[] output = new float[27];
        for (int coefficient = 0; coefficient < 9; coefficient++) {
            double convolution = coefficient == 0 ? Math.PI
                    : coefficient < 4 ? 2.0 * Math.PI / 3.0 : Math.PI / 4.0;
            for (int channel = 0; channel < 3; channel++) {
                int offset = coefficient * 3 + channel;
                output[offset] = (float) (sums[offset] * convolution * 4.0 * Math.PI / samples);
                if (!Float.isFinite(output[offset])) {
                    throw new IllegalArgumentException("Irradiance exceeds float storage range");
                }
            }
        }
        return output;
    }

    static void basis(Vector3f n, double[] result) {
        result[0] = 0.28209479177387814;
        result[1] = 0.4886025119029199 * n.y;
        result[2] = 0.4886025119029199 * n.z;
        result[3] = 0.4886025119029199 * n.x;
        result[4] = 1.0925484305920792 * n.x * n.y;
        result[5] = 1.0925484305920792 * n.y * n.z;
        result[6] = 0.31539156525252005 * (3.0 * n.z * n.z - 1.0);
        result[7] = 1.0925484305920792 * n.x * n.z;
        result[8] = 0.5462742152960396 * (n.x * n.x - n.y * n.y);
    }
}
