package com.jme3.environment.gi;

import com.jme3.math.Vector3f;

/**
 * Immutable, chunk-local grid descriptor for diffuse GI probes. This is not an
 * environment {@link com.jme3.light.LightProbe}. Counts need not be powers of two.
 * Use separate descriptors for resident world chunks; no probe storage is
 * allocated by this class. Positions are relative to the caller's world origin,
 * allowing large worlds to rebase without changing baked lighting.
 *
 * <pre>{@code
 * // 第一步：为一个驻留分块定义局部探针网格。
 * GiProbeGrid grid = new GiProbeGrid(new Vector3f(-16, 0, -16),
 *         new Vector3f(4, 2, 4), 9, 5, 9);
 * // 第二步：按 X 最快、其次 Y、最后 Z 的顺序访问探针。
 * Vector3f position = grid.position(grid.index(2, 1, 3), new Vector3f());
 * // 第三步：换块时替换描述符，不修改已发布的描述符。
 * }</pre>
 * All methods are thread safe when output vectors are not shared. Each axis
 * requires at least two probes and positive spacing; invalid inputs fail rather
 * than silently changing coverage. This descriptor does not install rendering
 * state, select a pipeline, or allocate GPU resources.
 */
public final class GiProbeGrid {
    private final Vector3f origin;
    private final Vector3f spacing;
    private final int xCount;
    private final int yCount;
    private final int zCount;
    private final int count;

    /** Creates an explicitly sized grid; spacing and origin are defensively copied. */
    public GiProbeGrid(Vector3f origin, Vector3f spacing, int xCount, int yCount, int zCount) {
        if (!Vector3f.isValidVector(origin) || !Vector3f.isValidVector(spacing)
                || spacing.x <= 0 || spacing.y <= 0 || spacing.z <= 0
                || xCount < 2 || yCount < 2 || zCount < 2) {
            throw new IllegalArgumentException("Finite origin, positive spacing and counts >= 2 required");
        }
        long xy = (long) xCount * yCount;
        if (xy > Integer.MAX_VALUE || xy * zCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Split this grid into smaller chunks");
        }
        Vector3f end = new Vector3f(origin.x + spacing.x * (xCount - 1),
                origin.y + spacing.y * (yCount - 1), origin.z + spacing.z * (zCount - 1));
        if (!Vector3f.isValidVector(end)) {
            throw new IllegalArgumentException("Grid extent overflows");
        }
        this.origin = origin.clone();
        this.spacing = spacing.clone();
        this.xCount = xCount;
        this.yCount = yCount;
        this.zCount = zCount;
        count = (int) (xy * zCount);
    }

    /** Returns the number of probes without allocating their data. */
    public int getProbeCount() {
        return count;
    }

    /** Returns a copy of the chunk-local origin. */
    public Vector3f getOrigin() {
        return origin.clone();
    }

    /** Returns a copy of the spacing, in chunk-local world units. */
    public Vector3f getSpacing() {
        return spacing.clone();
    }

    /** Returns X/Y/Z counts as a new vector for shader uniforms. */
    public Vector3f getCounts() {
        return new Vector3f(xCount, yCount, zCount);
    }

    /** Returns an X-major index; coordinates outside the grid are rejected. */
    public int index(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= xCount || y >= yCount || z >= zCount) {
            throw new IndexOutOfBoundsException("Probe coordinates outside grid");
        }
        return x + xCount * (y + yCount * z);
    }

    /** Writes a chunk-local position into the supplied vector. */
    public Vector3f position(int index, Vector3f store) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Probe index outside grid");
        }
        int x = index % xCount;
        int y = (index / xCount) % yCount;
        int z = index / (xCount * yCount);
        return store.set(origin.x + spacing.x * x, origin.y + spacing.y * y,
                origin.z + spacing.z * z);
    }

    /**
     * Writes eight neighboring indices and trilinear weights (X/Y/Z offset bits).
     * Returns false outside coverage and clears all weights, so a missing chunk
     * contributes no lighting. Boundary points use the final cell without wrapping.
     * Arrays must each have at least eight entries and are caller-owned.
     */
    public boolean neighbors(Vector3f point, int[] indices, float[] weights) {
        if (indices.length < 8 || weights.length < 8) {
            throw new IllegalArgumentException("Eight output entries required");
        }
        java.util.Arrays.fill(weights, 0, 8, 0f);
        java.util.Arrays.fill(indices, 0, 8, -1);
        if (!Vector3f.isValidVector(point)) {
            return false;
        }
        float x = (point.x - origin.x) / spacing.x;
        float y = (point.y - origin.y) / spacing.y;
        float z = (point.z - origin.z) / spacing.z;
        if (x < 0 || y < 0 || z < 0 || x > xCount - 1 || y > yCount - 1 || z > zCount - 1) {
            return false;
        }
        int bx = Math.min((int) Math.floor(x), xCount - 2);
        int by = Math.min((int) Math.floor(y), yCount - 2);
        int bz = Math.min((int) Math.floor(z), zCount - 2);
        float ax = x - bx;
        float ay = y - by;
        float az = z - bz;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1;
            int dy = (i >> 1) & 1;
            int dz = (i >> 2) & 1;
            indices[i] = index(bx + dx, by + dy, bz + dz);
            weights[i] = (dx == 0 ? 1 - ax : ax) * (dy == 0 ? 1 - ay : ay)
                    * (dz == 0 ? 1 - az : az);
        }
        return true;
    }
}
