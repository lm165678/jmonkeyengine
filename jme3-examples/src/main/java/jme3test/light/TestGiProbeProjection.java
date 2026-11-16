package jme3test.light;

import com.jme3.environment.gi.GiProbeBaker;
import com.jme3.environment.gi.GiProbeGrid;
import com.jme3.math.Vector3f;

/**
 * Headless numerical example of the low-level GI projection API.
 * Entry point: {@link #main(String[])}; no graphics capabilities or input controls
 * are required. This verifies projection only, not renderer integration.
 * Key APIs are GiProbeGrid and GiProbeBaker. Run this class as a Java application.
 * Automated regression command:
 * {@code ./gradlew :jme3-core:test --tests com.jme3.environment.gi.GiProbeTest}.
 *
 * <p>Grid coordinates are chunk-local and no scene or render state is changed.
 * A production capture backend supplies linear incident radiance instead of the
 * constant environment below. Its thread requirements apply to the synchronous
 * bake call. Cancellation discards partial output; publish only finished chunks.
 */
public final class TestGiProbeProjection {
    private TestGiProbeProjection() {
    }

    public static void main(String[] args) {
        // 第一步：定义分块局部坐标及探针间距，数量不必为二次幂。
        GiProbeGrid grid = new GiProbeGrid(new Vector3f(-8, 0, -8),
                new Vector3f(4, 2, 4), 5, 3, 5);
        // 第二步：使用均匀单位辐射环境验证积分；不访问渲染线程。
        GiProbeBaker.RadianceSource environment = (position, direction, result) -> result.set(1, 1, 1);
        // 第三步：逐个消费 SH9，不为整个世界分配一个巨大探针数组。
        GiProbeBaker.bake(grid, 1024, environment, (probe, coefficients) -> {
            double irradiance = coefficients[0] * 0.28209479177387814;
            if (Math.abs(irradiance - Math.PI) > 0.0001) {
                throw new AssertionError("Unexpected uniform-environment irradiance: " + irradiance);
            }
        }, () -> false);
        // 第四步：真实捕获需另行生成方向距离矩；SH 不能代替遮挡数据。
        System.out.println("Verified SH projection for " + grid.getProbeCount() + " probes.");
    }
}
