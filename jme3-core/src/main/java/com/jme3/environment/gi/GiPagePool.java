package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Renderer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.*;

/** Render-thread strip-page allocator. Source precision/resolution may differ;
 * pages use RGBA32F to avoid losing full-float data. CPU mirrors support context
 * restoration. Slots become visible only after all three payloads are uploaded.
 * No compaction: admission can fail from fragmentation, leaving existing slots intact.
 */
final class GiPagePool implements AutoCloseable {
    private static final int PAGE_ROWS = 4;
    private static final int BRICK_SLOTS = 128;
    static final int FINE_SLOTS = BRICK_SLOTS - GiProbeSet.MAX_VOLUMES;
    private static final int INDEX_WIDTH = 256;
    private static final int INDEX_ROWS = GiProbeStreamingData.MAX_BRICKS / (INDEX_WIDTH * 4);
    private static final int INDEX_HEIGHT = BRICK_SLOTS + GiProbeSet.MAX_VOLUMES * INDEX_ROWS;
    private final Renderer renderer;
    private final Texture2D[] textures;
    private final Texture2D metadata;
    private final Texture2D legacyMetadata;
    private final BitSet[] rows = {new BitSet(), new BitSet()};
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final GiProbeSet bindings;
    private final int width;
    private final int height;
    private final boolean bricks;
    private long uploaded;
    private boolean singleProbeVisibility;
    private boolean octahedralIrradiance;

    void setOctahedralIrradiance(boolean enabled) {
        if (octahedralIrradiance != enabled) {
            octahedralIrradiance = enabled;
            retain(java.util.Collections.emptySet());
        }
    }

    void setSingleProbeVisibility(boolean enabled) {
        if (singleProbeVisibility != enabled) {
            // Padding changes the slot shape. Rebuild before publishing any new
            // descriptors; never reinterpret an old layout with the new filter.
            retain(Collections.emptySet());
            singleProbeVisibility = enabled;
            textures[2].setMinFilter(enabled ? com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps
                    : com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
            textures[2].setMagFilter(enabled ? com.jme3.texture.Texture.MagFilter.Bilinear
                    : com.jme3.texture.Texture.MagFilter.Nearest);
        }
    }

    private static final class Slot {
        String id;
        GiProbeData data;
        Vector3f offset;
        float fade;
        float intensity = 1;
        boolean stabilized;
        boolean wide;
        boolean curved;
        boolean smooth;
        int index;
        int shRow;
        int visRow;
        int shPages;
        int visPages;
        int stage;
        int row;
        boolean published;
        boolean octahedral;
        Slot parent;
        int brick;
        GiProbeStreamingData directory;
    }

    GiPagePool(Renderer renderer, int limit, long bytes) {
        this(renderer, limit, bytes, false);
    }

    GiPagePool(Renderer renderer, int limit, long bytes, boolean spatialBricks) {
        this.renderer = Objects.requireNonNull(renderer);
        bricks = spatialBricks && limit >= INDEX_HEIGHT;
        width = Math.min(1024, limit);
        int indexWidth = bricks ? INDEX_WIDTH : 4;
        int indexHeight = bricks ? INDEX_HEIGHT : GiProbeSet.MAX_VOLUMES;
        int metadataBytes = indexWidth * indexHeight * 16;
        int legacyBytes = bricks ? GiProbeSet.MAX_VOLUMES * 64 : 0;
        int availableRows = (int) Math.min(limit, (bytes - metadataBytes - legacyBytes) / (3L * width * 16));
        height = availableRows / PAGE_ROWS * PAGE_ROWS;
        if (width < 9 || height < PAGE_ROWS) {
            throw new IllegalArgumentException("GI page budget too small");
        }
        textures = new Texture2D[3];
        for (int i = 0; i < 3; i++) {
            textures[i] = GiProbeData.texture(width, height,
                    BufferUtils.createByteBuffer(width * height * 16), false);
        }
        metadata = GiProbeData.texture(indexWidth, indexHeight, BufferUtils.createByteBuffer(metadataBytes), false);
        legacyMetadata = bricks ? GiProbeData.texture(4, GiProbeSet.MAX_VOLUMES,
                BufferUtils.createByteBuffer(legacyBytes), false) : null;
        bindings = new GiProbeSet(textures[0], textures[2], textures[1], metadata, legacyMetadata);
    }

    boolean supportsBricks() {
        return bricks;
    }

    GiProbeSet bindings() {
        return bindings;
    }

    long bytes() {
        return bindings.getTextureBytes();
    }

    long uploadedBytes() {
        return uploaded;
    }

    int publishedCount() {
        int count = 0;
        for (Slot slot : slots.values()) {
            if (slot.published) {
                count++;
            }
        }
        return count;
    }

    private int allocate(int bank, int pages) {
        int start = findRows(rows[bank], pages);
        if (start >= 0) {
            rows[bank].set(start, start + pages);
            return start * PAGE_ROWS;
        }
        return -1;
    }

    private int findRows(BitSet usedRows, int pages) {
        int count = height / PAGE_ROWS;
        for (int start = 0; start + pages <= count; start++) {
            int used = usedRows.nextSetBit(start);
            if (used < 0 || used >= start + pages) {
                return start;
            }
            start = used;
        }
        return -1;
    }

    // A newly attached coarse layer may reclaim fine pages. If even an atlas
    // containing only roots could not fit it, retain the current fine pages.
    boolean reclaimForCoarse(GiProbeData data) {
        if (!bricks || data.irradiance().getImage().getWidth() > width
                || data.moments().getImage().getWidth() > width) {
            return false;
        }
        Set<String> roots = new HashSet<>();
        BitSet sh = new BitSet();
        BitSet visibility = new BitSet();
        for (Slot slot : slots.values()) {
            if (slot.parent == null) {
                roots.add(slot.id);
                int a = slot.shRow / PAGE_ROWS;
                int b = slot.visRow / PAGE_ROWS;
                sh.set(a, a + slot.shPages);
                visibility.set(b, b + slot.visPages);
            }
        }
        if (roots.size() == GiProbeSet.MAX_VOLUMES || roots.size() == slots.size()
                || findRows(sh, pages(data.irradiance())) < 0
                || findRows(visibility, pages(data.moments())) < 0) {
            return false;
        }
        retain(roots);
        return true;
    }

    private int pages(Texture2D texture) {
        return (texture.getImage().getHeight() + PAGE_ROWS - 1) / PAGE_ROWS;
    }

    boolean contains(String id) {
        return slots.containsKey(id);
    }

    boolean isPublished(String id) {
        Slot slot = slots.get(id);
        return slot != null && slot.published;
    }

    boolean offer(String id, GiProbeData data, Vector3f offset, float fade) {
        return offer(id, data, offset, fade, null, null, 0);
    }

    boolean offer(String id, GiProbeData data, Vector3f offset, float fade,
            GiProbeStreamingData directory, String parentId, int brick) {
        if (slots.containsKey(id)) {
            return true;
        }
        if (data.irradiance().getImage().getWidth() > width
                || (singleProbeVisibility ? GiLinearMoments.width(data) : data.moments().getImage().getWidth()) > width) {
            return false;
        }
        Slot slot = new Slot();
        slot.directory = directory;
        slot.brick = brick;
        if (parentId != null) {
            slot.parent = slots.get(parentId);
            if (!bricks || slot.parent == null) {
                return false;
            }
        }
        BitSet indices = new BitSet();
        for (Slot existing : slots.values()) {
            indices.set(existing.index);
        }
        slot.index = indices.nextClearBit(slot.parent == null ? 0 : GiProbeSet.MAX_VOLUMES);
        if (slot.index >= (slot.parent == null ? GiProbeSet.MAX_VOLUMES : BRICK_SLOTS)) {
            return false;
        }
        slot.shPages = pages(data.irradiance());
        slot.visPages = singleProbeVisibility ? (GiLinearMoments.height(data) + PAGE_ROWS - 1) / PAGE_ROWS
                : pages(data.moments());
        slot.shRow = -1;
        if (octahedralIrradiance && data.hasOctahedralIrradiance()) {
            int octPages = (data.selectedIrradiance(true).getHeight() + PAGE_ROWS - 1) / PAGE_ROWS;
            slot.shRow = allocate(0, octPages);
            if (slot.shRow >= 0) {
                slot.octahedral = true;
                slot.shPages = octPages;
            }
        }
        if (slot.shRow < 0) {
            slot.shRow = allocate(0, slot.shPages);
        }
        slot.visRow = allocate(1, slot.visPages);
        if (slot.shRow < 0 || slot.visRow < 0) {
            if (slot.shRow >= 0) {
                rows[0].clear(slot.shRow / PAGE_ROWS, slot.shRow / PAGE_ROWS + slot.shPages);
            }
            if (slot.visRow >= 0) {
                rows[1].clear(slot.visRow / PAGE_ROWS, slot.visRow / PAGE_ROWS + slot.visPages);
            }
            return false;
        }
        slot.id = id;
        slot.data = data;
        slot.offset = offset.clone();
        slot.fade = fade;
        slots.put(id, slot);
        return true;
    }

    void appearance(String id, float intensity, float fade, boolean stabilized) {
        appearance(id, intensity, fade, stabilized, false);
    }

    void appearance(String id, float intensity, float fade, boolean stabilized, boolean smooth) {
        appearance(id, intensity, fade, stabilized, smooth, false);
    }

    void appearance(String id, float intensity, float fade, boolean stabilized, boolean smooth, boolean wide) {
        appearance(id, intensity, fade, stabilized, smooth, wide, false);
    }

    void appearance(String id, float intensity, float fade, boolean stabilized, boolean smooth, boolean wide, boolean curved) {
        Slot slot = slots.get(id);
        if (slot != null && (slot.intensity != intensity || slot.fade != fade || slot.stabilized != stabilized || slot.wide != wide || slot.curved != curved || slot.smooth)) {
            slot.wide = wide;
            slot.curved = curved;
            slot.smooth = false;
            slot.stabilized = stabilized;
            slot.intensity = intensity;
            slot.fade = fade;
            if (slot.published) {
                descriptor(slot, true);
            }
        }
    }

    void relocate(String id, Vector3f offset) {
        Slot slot = slots.get(id);
        if (slot != null && !slot.offset.equals(offset)) {
            slot.offset.set(offset);
            if (slot.published) {
                descriptor(slot, true);
            }
        }
    }

    void retain(Set<String> wanted) {
        Iterator<Slot> iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next();
            if (!wanted.contains(slot.id)) {
                if (slot.published) {
                    indexBrick(slot, false);
                    descriptor(slot, false);
                }
                rows[0].clear(slot.shRow / PAGE_ROWS, slot.shRow / PAGE_ROWS + slot.shPages);
                rows[1].clear(slot.visRow / PAGE_ROWS, slot.visRow / PAGE_ROWS + slot.visPages);
                iterator.remove();
            }
        }
    }

    void upload(long byteBudget, long nanos) {
        long start = System.nanoTime();
        long bytes = 0;
        for (Slot slot : slots.values()) {
            if (slot.published) {
                continue;
            }
            while (slot.stage < 3) {
                Image source = slot.stage == 0 ? slot.data.selectedIrradiance(slot.octahedral)
                        : slot.stage == 1 ? (slot.data.hasSeparatedSky() ? slot.data.skyVisibility().getImage() : null)
                        : slot.data.moments().getImage();
                Image shape = slot.stage == 0 ? slot.data.selectedIrradiance(slot.octahedral)
                        : slot.stage == 1 ? slot.data.irradiance().getImage() : slot.data.moments().getImage();
                boolean linearMoments = slot.stage == 2 && singleProbeVisibility;
                int sourceWidth = linearMoments ? GiLinearMoments.width(slot.data) : shape.getWidth();
                int sourceHeight = linearMoments ? GiLinearMoments.height(slot.data) : shape.getHeight();
                int rowBytes = sourceWidth * 16;
                if (bytes + rowBytes > byteBudget || System.nanoTime() - start >= nanos) {
                    return;
                }
                Texture2D target = textures[slot.stage];
                int rowStep = slot.stage == 1 && slot.octahedral ? GiProbeData.OCT_RECORD_ROWS : 1;
                int targetRow = (slot.stage < 2 ? slot.shRow : slot.visRow) + slot.row * rowStep;
                ByteBuffer cpu = target.getImage().getData(0);
                if (linearMoments) {
                    GiLinearMoments.copyRow(slot.data, slot.row, cpu, width, targetRow);
                } else {
                    for (int x = 0; x < sourceWidth; x++) {
                        for (int c = 0; c < 4; c++) {
                            float value = source == null ? 0 : GiProbeData.component(source,
                                    (slot.row * sourceWidth + x) * 4 + c);
                            cpu.putFloat(((targetRow * width + x) * 4 + c) * 4, value);
                        }
                    }
                }
                // CPU mirror is authoritative but is not marked wholly dirty.
                renderer.modifyTexture(target, target.getImage(), 0, targetRow,
                        0, targetRow, sourceWidth, 1);
                bytes += rowBytes;
                uploaded += rowBytes;
                slot.row++;
                if (slot.row == sourceHeight) {
                    slot.stage++;
                    slot.row = 0;
                }
            }
            int metadataBytes = bricks ? 96 + (slot.parent == null ? 64 : 0) : 64;
            if (bytes + metadataBytes + (slot.parent == null ? 0 : 16) > byteBudget
                    || System.nanoTime() - start >= nanos) {
                return;
            }
            descriptor(slot, true);
            indexBrick(slot, true);
            slot.published = true;
            bytes += metadataBytes + (slot.parent == null ? 0 : 16);
        }
    }

    private void indexBrick(Slot slot, boolean active) {
        if (slot.parent == null) {
            return;
        }
        int x = slot.brick / 4 % INDEX_WIDTH;
        int y = BRICK_SLOTS + slot.parent.index * INDEX_ROWS + slot.brick / (INDEX_WIDTH * 4);
        metadata.getImage().getData(0).putFloat((y * INDEX_WIDTH + x) * 16 + slot.brick % 4 * 4,
                active ? slot.index + 1 : 0);
        renderer.modifyTexture(metadata, metadata.getImage(), x, y, x, y, 1, 1);
        uploaded += 16;
    }

    private void descriptor(Slot slot, boolean active) {
        GiProbeData data = slot.data;
        GiProbeGrid grid = data.getGrid();
        Vector3f origin = grid.getOrigin().add(slot.offset);
        Vector3f spacing = grid.getSpacing();
        Vector3f counts = active ? grid.getCounts() : Vector3f.ZERO;
        float[] values = {origin.x, origin.y, origin.z, slot.fade,
            spacing.x, spacing.y, spacing.z, data.distanceScale(),
            counts.x, counts.y, counts.z, data.irradianceScale() * slot.intensity,
            data.columns(), slot.shRow, slot.visRow, data.resolution() + (data.isSurfaceLayout() ? 0.0625f : 0f) + (data.hasSeparatedSky() ? 0.5f : 0) + (slot.stabilized ? 0.125f : 0) + (slot.wide ? 0.015625f : 0) + (slot.curved ? 0.0078125f : 0)
                    + (singleProbeVisibility ? GiProbeSet.SINGLE_VISIBILITY : 0f)
                    + (slot.octahedral ? GiProbeSet.OCTAHEDRAL_IRRADIANCE : 0f)};
        int rowOffset = slot.index * metadata.getImage().getWidth() * 16;
        ByteBuffer buffer = metadata.getImage().getData(0);
        for (int c = 0; c < values.length; c++) {
            buffer.putFloat(rowOffset + c * 4, values[c]);
        }
        int columns = 4;
        if (bricks) {
            Vector3f blocks = slot.directory == null ? Vector3f.ZERO : slot.directory.brickCounts();
            Vector3f size = slot.directory == null ? Vector3f.ZERO : slot.directory.brickSize();
            for (int c = 0; c < 3; c++) {
                buffer.putFloat(rowOffset + 64 + c * 4, blocks.get(c));
                buffer.putFloat(rowOffset + 80 + c * 4, size.get(c));
            }
            columns = 6;
        }
        renderer.modifyTexture(metadata, metadata.getImage(), 0, slot.index, 0, slot.index, columns, 1);
        uploaded += columns * 16L;
        if (legacyMetadata != null && slot.parent == null) {
            ByteBuffer legacy = legacyMetadata.getImage().getData(0);
            for (int c = 0; c < values.length; c++) {
                legacy.putFloat(slot.index * 64 + c * 4, values[c]);
            }
            renderer.modifyTexture(legacyMetadata, legacyMetadata.getImage(), 0, slot.index, 0, slot.index, 4, 1);
            uploaded += 64;
        }
    }

    @Override
    public void close() {
        bindings.close();
        slots.clear();
    }
}
