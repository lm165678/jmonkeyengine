package jme3test.light;

/**
 * Current-frame half-resolution probe GI regression, sharing the regional fixture.
 * Entry main, OpenGL 4.3, offscreen, no controls. Requires seven floating-point
 * MRTs and 24 fragment texture units for optimized draws. Unsupported/Forward/
 * material-GI batches retain original queries. No rebake or temporal reuse.
 * Run {@code gradlew :jme3-examples:halfResolutionProbeGiSmoke}.
 * Tests overlapping volumes, coverage exit, automatic/explicit fades, odd sizes,
 * Forward/Deferred/CPU/GPU paths, and Off/On/Off restoration. Whole-image error
 * in both cache modes must stay within four RGBA8 codes in this synthetic
 * fixture; restoring Off must be pixel-identical. Real thin/curved surfaces
 * are approximations without full-query fallback.
 * Run TestSkyVolumeComposition with -Dgi.test.halfResolution=true (also
 * -Dgi.test.totalSky=true) for separate sky/PBR/reflection composition tests.
 * <pre>{@code
 * // 第一步：普通场景通过 WorldSettings 保存选项，不修改烘焙数据。
 * WorldSettings world = scene.getControl(WorldSettings.class);
 * world.setHalfResolutionProbeGi(true); // 默认 false；在应用线程操作。
 * // 第二步：手动管理时关闭自动服务，再配置视口。
 * getSceneServices().setEnabled(false);
 * viewPort.setHalfResolutionProbeGi(true);
 * // 第三步：检查薄墙和曲面；不可信像素回退原查询。
 * // 缓存位于材质漫反射响应之前，天空方向和覆盖一起重建。
 * // 第四步：需要测量不带回退的开销时，关闭独立质量保护。
 * viewPort.setProbeGiFullFallback(false); // 可能漏光；仅作对照。
 * viewPort.setProbeGiFullFallback(true);  // 默认，恢复完整查询回退。
 * // 第五步：关闭半分辨率恢复原路径；资源由视口 FrameGraph 管理。
 * viewPort.setHalfResolutionProbeGi(false);
 * }</pre>
 * Performance depends on fallback distribution; acceleration is not guaranteed.
 * Direct lights, reflections and UI retain their original resolution.
 */
public final class TestHalfResolutionProbeGi {
    private TestHalfResolutionProbeGi() {
    }

    public static void main(String[] args) {
        System.setProperty("gi.test.halfResolution", "true");
        TestGiProbeRegions.main(args);
    }
}
