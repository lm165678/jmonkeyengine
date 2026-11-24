package com.jme3.environment.gi;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;
import com.jme3.export.Savable;
import com.jme3.math.Vector3f;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Coarse probe data and a directory of independently compressed fine bricks.
 * The manifest is stored in the scene; its fine files are opened on demand.
 * Build from a regular grid on a worker after capture, then publish on the
 * scene thread. Use a fresh asset prefix for each revision.
 *
 * <pre>{@code
 * // 第一步：在场景线程取得普通网格烘焙结果。
 * GiProbeData fine = volume.getData();
 * // 第二步：后台生成粗层并保存细块，输出由调用者定位到资产目录。
 * GiProbeStreamingData baked = GiProbeStreamingData.build(fine, "GI/world-v1",
 *         key -> java.nio.file.Files.newOutputStream(assetRoot.resolve(key)));
 * // 第三步：回到场景线程发布，保存 J3O 并一同打包细块。
 * volume.useStreamingData(baked, fine);
 * // 第四步：SimpleApplication 挂载场景后自动流送。细块缺失时使用粗层。
 * }</pre>
 */
public final class GiProbeStreamingData implements Savable {
    static final int CELLS = 4;
    static final int MAX_BRICKS = 32768;
    private static final int HALO = 2;
    private GiProbeData coarse;
    private Vector3f fineCounts;
    private Vector3f fineSpacing;
    private String prefix;
    private long[] brickBytes;

    /** Required by the importer. Use {@link #build} to create a manifest. */
    public GiProbeStreamingData() {
    }

    /** Opens an output under the project asset key. Each stream is closed by the builder. */
    @FunctionalInterface
    public interface Output {
        OutputStream open(String assetKey) throws IOException;
    }

    /**
     * Writes fine blocks of four cells per axis with a two-probe sampling halo.
     * Exact fine payloads are copied. The fallback selects original sample
     * positions, preserving offsets and distance encoding, and caps coarse
     * counts at eight per axis. Publication is the caller's responsibility.
     *
     * @param source completed regular bake
     * @param assetPrefix unique relative prefix, without extension
     * @param output output factory
     * @return publishable directory after all writes succeed
     * @throws IOException if a fine brick could not be written
     */
    public static GiProbeStreamingData build(GiProbeData source, String assetPrefix, Output output) throws IOException {
        if (source.isSurfaceLayout() || !validPrefix(assetPrefix)) {
            throw new IllegalArgumentException("Regular GI grid and a relative asset prefix required");
        }
        GiProbeGrid grid = source.getGrid();
        GiProbeStreamingData result = new GiProbeStreamingData();
        result.fineCounts = grid.getCounts();
        result.fineSpacing = grid.getSpacing();
        result.prefix = assetPrefix;
        int nx = (int) result.fineCounts.x;
        int ny = (int) result.fineCounts.y;
        int nz = (int) result.fineCounts.z;
        int bx = brickCount(nx);
        int by = brickCount(ny);
        int bz = brickCount(nz);
        if ((long) bx * by * bz > MAX_BRICKS) {
            throw new IllegalArgumentException("GI volume exceeds the fine brick directory capacity");
        }
        result.brickBytes = new long[bx * by * bz];
        result.coarse = source.resample(0, 0, 0, nx, ny, nz,
                coarseCount(nx), coarseCount(ny), coarseCount(nz), Math.min(source.resolution(), 8));
        boolean complete = false;
        try {
            for (int i = 0; i < result.brickBytes.length; i++) {
                int x = (i % bx) * CELLS;
                int y = (i / bx % by) * CELLS;
                int z = (i / (bx * by)) * CELLS;
                int x0 = Math.max(0, x - HALO);
                int y0 = Math.max(0, y - HALO);
                int z0 = Math.max(0, z - HALO);
                int sx = Math.min(nx - 1, x + CELLS + HALO) - x0 + 1;
                int sy = Math.min(ny - 1, y + CELLS + HALO) - y0 + 1;
                int sz = Math.min(nz - 1, z + CELLS + HALO) - z0 + 1;
                GiProbeData brick = source.resample(x0, y0, z0, sx, sy, sz, sx, sy, sz, source.resolution());
                try (OutputStream stream = output.open(result.assetKey(i))) {
                    brick.saveCompressed(stream);
                    result.brickBytes[i] = brick.getTextureBytes();
                } finally {
                    brick.dispose();
                }
            }
            complete = true;
            return result;
        } finally {
            if (!complete) {
                result.coarse.dispose();
            }
        }
    }

    private static boolean validPrefix(String key) {
        return key != null && !key.isEmpty() && !key.startsWith("/") && !key.contains("\\")
                && !key.contains(":") && !key.contains("..");
    }

    private static int coarseCount(int count) {
        return Math.max(2, Math.min(8, (count + 1) / 2));
    }

    private static int brickCount(int count) {
        return (count - 2) / CELLS + 1;
    }

    Vector3f brickCounts() {
        return new Vector3f(brickCount((int) fineCounts.x), brickCount((int) fineCounts.y), brickCount((int) fineCounts.z));
    }

    Vector3f brickSize() {
        return fineSpacing.mult(CELLS);
    }

    Vector3f extent() {
        return fineCounts.subtract(1, 1, 1).multLocal(fineSpacing);
    }

    /** @return the embedded fallback, owned by this bake revision */
    public GiProbeData getCoarseData() {
        return coarse;
    }

    /**
     * Returns the original fine grid in bake-local world units, without I/O.
     * Counts are those of the completed bake, even if the editor settings changed.
     * <pre>{@code
     * // 第一步：取得已发布的流式烘焙目录。
     * GiProbeStreamingData baked = volume.getStreamingData();
     * // 第二步：仅查看细层布局，不加载或更改探针数据。
     * GiProbeGrid grid = baked.getFineGrid();
     * }</pre>
     * @return immutable fine-grid descriptor
     */
    public GiProbeGrid getFineGrid() {
        return new GiProbeGrid(coarse.getGrid().getOrigin(), fineSpacing,
                (int) fineCounts.x, (int) fineCounts.y, (int) fineCounts.z);
    }

    int size() {
        return brickBytes.length;
    }

    long bytes(int i) {
        return brickBytes[i];
    }

    int probes(int i) {
        Vector3f counts = brickCounts();
        int x = i % (int) counts.x * CELLS;
        int y = i / (int) counts.x % (int) counts.y * CELLS;
        int z = i / ((int) counts.x * (int) counts.y) * CELLS;
        return axisProbes(x, (int) fineCounts.x) * axisProbes(y, (int) fineCounts.y)
                * axisProbes(z, (int) fineCounts.z);
    }

    private static int axisProbes(int first, int count) {
        return Math.min(count - 1, first + CELLS + HALO) - Math.max(0, first - HALO) + 1;
    }

    private String assetKey(int i) {
        return prefix + "-" + i + ".giprobe";
    }

    GiProbeData load(AssetManager assets, int i) throws IOException {
        return load(assets, i, true);
    }

    GiProbeData load(AssetManager assets, int i, boolean octahedral) throws IOException {
        String key = assetKey(i);
        AssetInfo info = assets.locateAsset(new AssetKey<>(key));
        if (info == null) {
            throw new FileNotFoundException(key);
        }
        try (InputStream input = info.openStream()) {
            return GiProbeData.loadCompressed(input, octahedral);
        }
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule out = exporter.getCapsule(this);
        out.write(coarse, "coarse", null);
        out.write(fineCounts, "fineCounts", null);
        out.write(fineSpacing, "fineSpacing", null);
        out.write(prefix, "prefix", null);
        out.write(brickBytes, "brickBytes", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule in = importer.getCapsule(this);
        coarse = (GiProbeData) in.readSavable("coarse", null);
        fineCounts = (Vector3f) in.readSavable("fineCounts", null);
        fineSpacing = (Vector3f) in.readSavable("fineSpacing", null);
        prefix = in.readString("prefix", null);
        brickBytes = in.readLongArray("brickBytes", null);
        if (coarse == null || coarse.isSurfaceLayout() || !validPrefix(prefix)
                || !Vector3f.isValidVector(fineCounts) || !Vector3f.isValidVector(fineSpacing)
                || fineCounts.x < 2 || fineCounts.y < 2 || fineCounts.z < 2
                || fineCounts.x != (int) fineCounts.x || fineCounts.y != (int) fineCounts.y
                || fineCounts.z != (int) fineCounts.z || fineSpacing.x <= 0
                || fineSpacing.y <= 0 || fineSpacing.z <= 0 || brickBytes == null
                || brickBytes.length > MAX_BRICKS) {
            throw new IOException("Invalid GI brick directory");
        }
        Vector3f dims = brickCounts();
        if (dims.x * dims.y * dims.z != brickBytes.length) {
            throw new IOException("Invalid GI brick count");
        }
        for (long bytes : brickBytes) {
            if (bytes <= 0) {
                throw new IOException("Invalid GI brick payload bound");
            }
        }
    }
}
