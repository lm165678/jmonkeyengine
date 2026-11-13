package com.jme3.scene.plugins.sdf;

/** Immutable CPU bake settings. Samples include both ends of each grid axis. */
public final class SdfSettings {
    /** Sign convention; neither mode repairs meshes or performs boolean union. */
    public enum SignMode {
        /** Negative inside closed shells using even/odd crossings; nested shells form cavities.
         * 闭合、不自交表面使用此模式；无需统一法线方向。重叠实体按奇偶规则，不是布尔并集。
         * 开放表面、相交或重复面不能保证内部符号；工具不会自动修补模型。
         */
        EVEN_ODD,
        /** Non-negative distance to all triangles, suitable for open surfaces.
         * 建筑薄片、布料、开放模型可用此模式，只有距离，不承诺内外。
         */
        UNSIGNED
    }

    final int x;
    final int y;
    final int z;
    final float padding;
    final SignMode signMode;

    /**
     * Creates a bake configuration. Padding is a fraction of the longest model bound
     * dimension, added to BOTH ends of all axes. Units remain model-root local units.
     * <pre>{@code
     * // 第一步：每轴 64 个采样点，最长边的 10% 作为四周留白。
     * SdfSettings settings = new SdfSettings(64, 64, 64, .1f, SdfSettings.SignMode.EVEN_ODD);
     * // 第二步：开放表面可改为 UNSIGNED；增加分辨率会立方级增加内存/计算量。
     * // 64^3 的 float 结果约 1 MiB；还需三角形快照和 BVH 内存。
     * }</pre>
     * @param x samples on X, at least 2
     * @param y samples on Y, at least 2
     * @param z samples on Z, at least 2
     * @param padding positive finite relative padding
     * @param signMode inside/outside policy
     */
    public SdfSettings(int x, int y, int z, float padding, SignMode signMode) {
        sampleCount(x, y, z);
        if (!Float.isFinite(padding) || padding <= 0 || signMode == null) {
            throw new IllegalArgumentException("Positive finite padding and sign mode required");
        }
        this.x = x;
        this.y = y;
        this.z = z;
        this.padding = padding;
        this.signMode = signMode;
    }

    static int sampleCount(int x, int y, int z) {
        if (x < 2 || y < 2 || z < 2 || x > 1024 || y > 1024 || z > 1024
                || (long) x * y * z > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Grid requires axes 2..1024 and at most 64M samples");
        }
        return x * y * z;
    }
}
