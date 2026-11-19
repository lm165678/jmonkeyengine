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
    private final Renderer renderer;
    private final Texture2D[] textures;
    private final Texture2D metadata;
    private final BitSet[] rows = {new BitSet(), new BitSet()};
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final GiProbeSet bindings;
    private final int width;
    private final int height;
    private long uploaded;

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
    }

    GiPagePool(Renderer renderer, int limit, long bytes) {
        this.renderer = Objects.requireNonNull(renderer);
        width = Math.min(1024, limit);
        int availableRows = (int) Math.min(limit, (bytes - 1024) / (3L * width * 16));
        height = availableRows / PAGE_ROWS * PAGE_ROWS;
        if (width < 9 || height < PAGE_ROWS) {
            throw new IllegalArgumentException("GI page budget too small");
        }
        textures = new Texture2D[3];
        for (int i = 0; i < 3; i++) {
            textures[i] = GiProbeData.texture(width, height,
                    BufferUtils.createByteBuffer(width * height * 16), false);
        }
        metadata = GiProbeData.texture(4, GiProbeSet.MAX_VOLUMES,
                BufferUtils.createByteBuffer(GiProbeSet.MAX_VOLUMES * 64), false);
        bindings = new GiProbeSet(textures[0], textures[2], textures[1], metadata);
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
        int count = height / PAGE_ROWS;
        for (int start = 0; start + pages <= count; start++) {
            int used = rows[bank].nextSetBit(start);
            if (used < 0 || used >= start + pages) {
                rows[bank].set(start, start + pages);
                return start * PAGE_ROWS;
            }
            start = used;
        }
        return -1;
    }

    boolean contains(String id) {
        return slots.containsKey(id);
    }

    boolean offer(String id, GiProbeData data, Vector3f offset, float fade) {
        if (slots.containsKey(id)) {
            return true;
        }
        if (slots.size() == GiProbeSet.MAX_VOLUMES
                || data.irradiance().getImage().getWidth() > width
                || data.moments().getImage().getWidth() > width) {
            return false;
        }
        Slot slot = new Slot();
        slot.shPages = (data.irradiance().getImage().getHeight() + PAGE_ROWS - 1) / PAGE_ROWS;
        slot.visPages = (data.moments().getImage().getHeight() + PAGE_ROWS - 1) / PAGE_ROWS;
        slot.shRow = allocate(0, slot.shPages);
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
        BitSet indices = new BitSet();
        for (Slot existing : slots.values()) {
            indices.set(existing.index);
        }
        slot.index = indices.nextClearBit(0);
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
                Image source = slot.stage == 0 ? slot.data.irradiance().getImage()
                        : slot.stage == 1 ? (slot.data.hasSeparatedSky() ? slot.data.skyVisibility().getImage() : null)
                        : slot.data.moments().getImage();
                Image shape = slot.stage < 2 ? slot.data.irradiance().getImage() : slot.data.moments().getImage();
                int rowBytes = shape.getWidth() * 16;
                if (bytes + rowBytes > byteBudget || System.nanoTime() - start >= nanos) {
                    return;
                }
                Texture2D target = textures[slot.stage];
                int targetRow = (slot.stage < 2 ? slot.shRow : slot.visRow) + slot.row;
                ByteBuffer cpu = target.getImage().getData(0);
                for (int x = 0; x < shape.getWidth(); x++) {
                    for (int c = 0; c < 4; c++) {
                        float value = source == null ? 0 : GiProbeData.component(source,
                                (slot.row * shape.getWidth() + x) * 4 + c);
                        cpu.putFloat(((targetRow * width + x) * 4 + c) * 4, value);
                    }
                }
                // CPU mirror is authoritative but is not marked wholly dirty.
                renderer.modifyTexture(target, target.getImage(), 0, targetRow,
                        0, targetRow, shape.getWidth(), 1);
                bytes += rowBytes;
                uploaded += rowBytes;
                slot.row++;
                if (slot.row == shape.getHeight()) {
                    slot.stage++;
                    slot.row = 0;
                }
            }
            if (bytes + 64 > byteBudget || System.nanoTime() - start >= nanos) {
                return;
            }
            descriptor(slot, true);
            slot.published = true;
            bytes += 64;
        }
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
            data.columns(), slot.shRow, slot.visRow, data.resolution() + (data.isSurfaceLayout() ? 0.0625f : 0f) + (data.hasSeparatedSky() ? 0.5f : 0) + (slot.stabilized ? 0.125f : 0) + (slot.wide ? 0.015625f : 0) + (slot.curved ? 0.0078125f : 0)};
        for (int c = 0; c < 16; c++) {
            metadata.getImage().getData(0).putFloat(slot.index * 64 + c * 4, values[c]);
        }
        renderer.modifyTexture(metadata, metadata.getImage(), 0, slot.index, 0, slot.index, 4, 1);
        uploaded += 64;
    }

    @Override
    public void close() {
        bindings.close();
        slots.clear();
    }
}
