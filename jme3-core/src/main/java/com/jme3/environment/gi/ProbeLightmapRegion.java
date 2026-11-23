package com.jme3.environment.gi;

import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Worker-loaded snapshot of the probes touching one receiver region. Reuses the runtime page
 * pool for overlapping coarse/fine bakes, so directory and boundary blending stay identical.
 * <pre>{@code
 * // 第一步：在场景线程记录区域的 Volume 和网格世界包围盒。
 * ProbeLightmapRegion.Source source = ProbeLightmapRegion.Source.capture(volume);
 * // 第二步：在后台读取该区域的探针数据。包围盒须为调用方的独立副本。
 * ProbeLightmapRegion region = ProbeLightmapRegion.load(assetManager, List.of(source), bounds, bytes, true);
 * // 第三步：在渲染线程建图集、烘焙并关闭临时图集。
 * try (GiProbeSet field = region.open(renderManager, bytes, true, true)) {
 *     Texture2D texture = ProbeLightmapBaker.bakeField(renderManager, assetManager, geometry, field, 256, 256);
 * }
 * }</pre>
 */
public final class ProbeLightmapRegion {
    /** A placement and appearance snapshot, safe for background reads after capture. */
    public static final class Source {
        private final GiProbeData embedded;
        private final GiProbeStreamingData directory;
        private final String asset;
        private final long assetBytes;
        private final Vector3f offset;
        private final BoundingBox bounds;
        private final float fade;
        private final float intensity;
        private final boolean stabilized;
        private final boolean wide;
        private final boolean curved;

        private Source(GiProbeVolume volume, GiProbeData embedded, GiProbeStreamingData directory, String asset) {
            this.embedded = embedded;
            this.directory = directory;
            this.asset = asset;
            assetBytes = volume.getStreamBytes();
            offset = volume.getWorldTranslation().clone();
            GiProbeGrid grid = embedded != null ? embedded.getGrid()
                    : directory != null ? directory.getCoarseData().getGrid() : volume.getBakeGrid();
            Vector3f origin = grid.getOrigin().add(offset);
            Vector3f extent = grid.getCounts().subtract(1, 1, 1).multLocal(grid.getSpacing()).multLocal(.5f);
            bounds = new BoundingBox(origin.add(extent), extent.x, extent.y, extent.z);
            fade = volume.getFadeDistance();
            intensity = volume.getIntensity();
            stabilized = volume.isStabilizedBlending();
            wide = volume.isWideProbeSampling();
            curved = volume.isCurvedReceiverCorrection();
        }

        /** @return a snapshot of the completed source, or null while absent or invalid */
        public static Source capture(GiProbeVolume volume) {
            GiProbeData data = volume.getData();
            GiProbeStreamingData directory = volume.getStreamingData();
            String asset = volume.getStreamAsset();
            return data == null && directory == null && asset == null ? null : new Source(volume, data, directory, asset);
        }

        /** @return whether this source's bounds intersect the receiver */
        public boolean intersects(BoundingVolume receiver) {
            return receiver != null && bounds.intersects(receiver);
        }

        /** @return whether building this field requires selecting fine bricks by receiver bounds */
        public boolean hasBricks() {
            return directory != null;
        }

        private GiProbeData read(AssetManager assets, boolean octahedral) throws IOException {
            if (embedded != null) {
                return embedded;
            }
            if (directory != null) {
                return directory.getCoarseData();
            }
            var info = assets.locateAsset(new AssetKey<>(asset));
            if (info == null) {
                throw new IOException("Missing probe field " + asset);
            }
            try (var input = info.openStream()) {
                return GiProbeData.loadCompressed(input, octahedral);
            }
        }
    }

    private static final class Brick {
        final int index;
        final GiProbeData data;

        Brick(int index, GiProbeData data) {
            this.index = index;
            this.data = data;
        }
    }

    private static final class Root {
        final Source source;
        final GiProbeData data;
        final List<Brick> bricks = new ArrayList<>();

        Root(Source source, GiProbeData data) {
            this.source = source;
            this.data = data;
        }
    }

    private final List<Root> roots = new ArrayList<>();

    private ProbeLightmapRegion() {
    }

    /** Reads all intersecting roots and fine bricks off the render thread, within a CPU byte budget. */
    public static ProbeLightmapRegion load(AssetManager assets, List<Source> sources, BoundingVolume receiver,
            long bytes, boolean octahedral) throws IOException {
        ProbeLightmapRegion region = new ProbeLightmapRegion();
        for (Source source : sources) {
            if (!source.intersects(receiver)) {
                continue;
            }
            if (region.roots.size() == GiProbeSet.MAX_VOLUMES || source.assetBytes > bytes) {
                throw new IOException("Probe lightmap region exceeds the source budget");
            }
            Root root = new Root(source, source.read(assets, octahedral));
            bytes -= root.data.getTextureBytes();
            if (bytes < 0) {
                throw new IOException("Probe lightmap region exceeds the source budget");
            }
            if (source.directory != null) {
                bytes = loadBricks(assets, receiver, root, bytes, octahedral);
            }
            region.roots.add(root);
        }
        if (region.roots.isEmpty()) {
            throw new IOException("No probe volumes cover this lightmap receiver");
        }
        return region;
    }

    private static long loadBricks(AssetManager assets, BoundingVolume receiver, Root root, long bytes,
            boolean octahedral) throws IOException {
        GiProbeStreamingData directory = root.source.directory;
        Vector3f counts = directory.brickCounts();
        Vector3f size = directory.brickSize();
        Vector3f extent = directory.extent();
        Vector3f origin = root.data.getGrid().getOrigin().add(root.source.offset);
        Vector3f halo = size.mult(.25f);
        BoundingBox bounds = new BoundingBox();
        for (int i = 0; i < directory.size(); i++) {
            float x = i % (int) counts.x * size.x;
            float y = i / (int) counts.x % (int) counts.y * size.y;
            float z = i / ((int) counts.x * (int) counts.y) * size.z;
            Vector3f half = new Vector3f(Math.min(size.x, extent.x - x), Math.min(size.y, extent.y - y),
                    Math.min(size.z, extent.z - z)).multLocal(.5f);
            bounds.setCenter(origin.add(x, y, z).addLocal(half));
            bounds.setXExtent(half.x + halo.x);
            bounds.setYExtent(half.y + halo.y);
            bounds.setZExtent(half.z + halo.z);
            if (!bounds.intersects(receiver)) {
                continue;
            }
            if (directory.bytes(i) > bytes) {
                throw new IOException("Fine probes exceed the lightmap source budget");
            }
            GiProbeData brick = directory.load(assets, i, octahedral);
            bytes -= brick.getTextureBytes();
            if (bytes < 0) {
                throw new IOException("Fine probes exceed the lightmap source budget");
            }
            root.bricks.add(new Brick(i, brick));
        }
        return bytes;
    }

    /** Builds the render-thread atlas. The caller closes it after rasterization. */
    public GiProbeSet open(RenderManager manager, long bytes, boolean singleVisibility, boolean octahedral) {
        if (roots.stream().noneMatch(root -> root.source.hasBricks())) {
            List<GiProbeSet.Entry> entries = new ArrayList<>(roots.size());
            for (Root root : roots) {
                Source source = root.source;
                entries.add(new GiProbeSet.Entry(root.data, source.offset, source.fade, source.intensity,
                        source.stabilized, false, source.wide, source.curved));
            }
            return ProbeLightmapBaker.openField(manager, entries, bytes, singleVisibility, octahedral);
        }
        int limit = manager.getRenderer().getLimits().getOrDefault(Limits.TextureSize, 0);
        GiPagePool pages = new GiPagePool(manager.getRenderer(), limit, bytes, true);
        try {
            if (!pages.supportsBricks()) {
                throw new IllegalArgumentException("Renderer cannot hold the fine probe directory");
            }
            pages.setSingleProbeVisibility(singleVisibility);
            pages.setOctahedralIrradiance(octahedral);
            for (int i = 0; i < roots.size(); i++) {
                Root root = roots.get(i);
                Source source = root.source;
                String id = "root/" + i;
                offer(pages, id, root.data, source, source.directory, null, 0);
            }
            for (int i = 0; i < roots.size(); i++) {
                Root root = roots.get(i);
                String id = "root/" + i;
                for (Brick brick : root.bricks) {
                    offer(pages, id + "/" + brick.index, brick.data, root.source, null, id, brick.index);
                }
            }
            pages.upload(Long.MAX_VALUE, Long.MAX_VALUE);
            return pages.bindings();
        } catch (RuntimeException | Error failure) {
            pages.close();
            throw failure;
        }
    }

    private static void offer(GiPagePool pages, String id, GiProbeData data, Source source,
            GiProbeStreamingData directory, String parent, int brick) {
        if (!pages.offer(id, data, source.offset, source.fade, directory, parent, brick)) {
            throw new IllegalArgumentException("Fine probe field exceeds the lightmap atlas budget");
        }
        pages.appearance(id, source.intensity, source.fade, source.stabilized, false, source.wide, source.curved);
    }
}
