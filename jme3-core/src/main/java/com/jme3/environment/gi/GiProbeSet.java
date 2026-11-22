package com.jme3.environment.gi;

import com.jme3.material.*;
import com.jme3.math.*;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * <p>SimpleApplication automatically manages attached GiProbeVolume nodes through
 * SceneServicesState. For explicit low-level bindings/streaming as below, call
 * {@code app.getSceneServices().setEnabled(false)} before installing manual
 * bindings. Close manual bindings before enabling automatic services again.
 * Standalone data not attached as a volume is unaffected.</p>
 *

 * Bounded resident atlas for up to 16 overlapping volumes, including mixed probe
 * densities and visibility resolutions. Four texture units serve all volumes, including separated sky visibility.
 * Selection/blending is per fragment, so a large mesh/material can cross chunks.
 * Boundary weights use a separable quintic window (one cell per axis for zero
 * explicit fade), avoiding nearest-face ridges at brick corners. Weights are
 * normalized across available volumes; fine grids have
 * higher weight using inverse geometric-mean spacing. Provide overlapping coverage for continuous transitions; an
 * isolated volume contributes zero outside coverage. Invalid/occluded probes do
 * not contribute. Empty sets should be represented by closing the old binding.
 *
 * <pre>{@code
 * // 第一步：收集驻留的体积；可使用不同探针密度和距离矩分辨率。
 * List<GiProbeSet.Entry> entries = new ArrayList<>();
 * entries.add(new GiProbeSet.Entry(a.getData(), a.getWorldTranslation(), 2f));
 * entries.add(new GiProbeSet.Entry(b.getData(), b.getWorldTranslation(), 2f));
 * // 第二步：在纹理尺寸和预算内创建快照；大模型可以共享这一个绑定。
 * GiProbeSet set = new GiProbeSet(entries, 4096, 64L * 1024 * 1024);
 * GiProbeSet.Binding binding = set.bind(material, 0.05f);
 * // 第三步：换驻留集合时创建新快照、关闭旧绑定、绑定新快照并释放旧 set。
 * binding.close();
 * set.close();
 * }</pre>
 * Construct/bind/close on the render thread. Construction does not change source
 * data. A set owns only its copied atlases; caller retains the individual chunks.
 * maxBytes bounds logical texture payload, not driver/bindless duplicate caches.
 */
public final class GiProbeSet implements AutoCloseable {
    public static final int MAX_VOLUMES = 16;

    /** Immutable placement snapshot. fadeDistance is in world units, nonnegative. */
    public static final class Entry {
        final GiProbeData data;
        final Vector3f offset;
        final float fade;
        final float intensity;
        final boolean stabilized;
        final boolean wide;
        final boolean curved;
        final boolean smooth;

        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance) {
            this(data, worldOffset, fadeDistance, 1f);
        }

        /** Sets the runtime strength. With zero fade, the volume contributes fully up to its boundary. */
        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance, float intensity) {
            this(data, worldOffset, fadeDistance, intensity, false);
        }

        /** Low-level atlas option; see GiProbeVolume.setStabilizedBlending.
         * <pre>{@code
         * // 第一步：仅手动图集扩展使用此入口；普通 J3O 使用 Volume 设置。
         * GiProbeSet.Entry entry = new GiProbeSet.Entry(data, offset, 0f, 1f, true);
         * // 第二步：恢复原算法时用 false 重建 Entry/图集，关闭旧 binding 后替换。
         * }</pre>
         */
        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance,
                float intensity, boolean stabilized) {
            this(data, worldOffset, fadeDistance, intensity, stabilized, false);
        }

        /** Compatibility overload. The removed smooth argument is ignored; use the five-argument constructor. */
        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance,
                float intensity, boolean stabilized, boolean smooth) {
            this(data, worldOffset, fadeDistance, intensity, stabilized, smooth, false);
        }

        /** Low-level explicit stencil selection; see GiProbeVolume.setWideProbeSampling. */
        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance,
                float intensity, boolean stabilized, boolean smooth, boolean wide) {
            this(data, worldOffset, fadeDistance, intensity, stabilized, smooth, wide, false);
        }

        /** Explicit curved-receiver option, default false in older overloads.
         * See GiProbeVolume.setCurvedReceiverCorrection for usage and restoration.
         * Manual atlas callers rebuild Entry/set and close old bindings to switch. */
        public Entry(GiProbeData data, Vector3f worldOffset, float fadeDistance,
                float intensity, boolean stabilized, boolean smooth, boolean wide, boolean curved) {
            this.curved = curved;
            this.wide = wide;
            this.smooth = false;
            this.stabilized = stabilized;
            this.data = Objects.requireNonNull(data);
            if (!Vector3f.isValidVector(worldOffset) || !Float.isFinite(fadeDistance) || fadeDistance < 0
                    || !Float.isFinite(intensity) || intensity < 0) {
                throw new IllegalArgumentException("Finite offset and nonnegative fade/intensity required");
            }
            offset = worldOffset.clone();
            fade = fadeDistance;
            this.intensity = intensity;
        }
    }

    private final Texture2D sh;
    private final Texture2D moments;
    private final Texture2D skyVisibility;
    private final Texture2D metadata;
    private final int count;
    private boolean closed;

    public GiProbeSet(List<Entry> entries, int maxTextureSize, long maxBytes) {
        if (entries.isEmpty() || entries.size() > MAX_VOLUMES) {
            throw new IllegalArgumentException("A resident set requires 1..16 volumes");
        }
        count = entries.size();
        int shWidth = 0;
        int shHeight = 0;
        int visWidth = 0;
        int visHeight = 0;
        boolean compact = true;
        for (Entry entry : entries) {
            Image a = entry.data.irradiance().getImage();
            Image b = entry.data.moments().getImage();
            shWidth = Math.max(shWidth, a.getWidth());
            visWidth = Math.max(visWidth, b.getWidth());
            shHeight = Math.addExact(shHeight, a.getHeight());
            visHeight = Math.addExact(visHeight, b.getHeight());
            compact &= a.getFormat() == Image.Format.RGBA16F && b.getFormat() == Image.Format.RGBA16F;
        }
        long bytes = (2L * shWidth * shHeight + (long) visWidth * visHeight) * (compact ? 8 : 16) + count * 64L;
        if (maxTextureSize < 4 || shWidth > maxTextureSize || shHeight > maxTextureSize
                || visWidth > maxTextureSize || visHeight > maxTextureSize || bytes > maxBytes || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Resident GI atlas exceeds texture size or byte budget");
        }
        ByteBuffer shBytes = BufferUtils.createByteBuffer(shWidth * shHeight * (compact ? 8 : 16));
        ByteBuffer skyBytes = BufferUtils.createByteBuffer(shBytes.capacity());
        ByteBuffer visBytes = BufferUtils.createByteBuffer(visWidth * visHeight * (compact ? 8 : 16));
        ByteBuffer descriptors = BufferUtils.createByteBuffer(count * 64);
        int shRow = 0;
        int visRow = 0;
        for (int i = 0; i < count; i++) {
            Entry entry = entries.get(i);
            GiProbeData data = entry.data;
            GiProbeGrid grid = data.getGrid();
            Vector3f origin = grid.getOrigin().add(entry.offset);
            Vector3f spacing = grid.getSpacing();
            Vector3f counts = grid.getCounts();
            float[] descriptor = {origin.x, origin.y, origin.z, entry.fade,
                spacing.x, spacing.y, spacing.z, data.distanceScale(),
                counts.x, counts.y, counts.z, data.irradianceScale() * entry.intensity,
                data.columns(), shRow, visRow, data.resolution() + (data.isSurfaceLayout() ? 0.0625f : 0f) + (data.hasSeparatedSky() ? 0.5f : 0f) + (entry.stabilized ? 0.125f : 0f) + (entry.wide ? 0.015625f : 0f) + (entry.curved ? 0.0078125f : 0f)};
            for (int c = 0; c < 16; c++) {
                descriptors.putFloat((i * 16 + c) * 4, descriptor[c]);
            }
            copy(data.irradiance().getImage(), shBytes, shWidth, shRow, compact);
            if (data.hasSeparatedSky()) {
                copy(data.skyVisibility().getImage(), skyBytes, shWidth, shRow, compact);
            }
            copy(data.moments().getImage(), visBytes, visWidth, visRow, compact);
            shRow += data.irradiance().getImage().getHeight();
            visRow += data.moments().getImage().getHeight();
        }
        sh = GiProbeData.texture(shWidth, shHeight, shBytes, compact);
        skyVisibility = GiProbeData.texture(shWidth, shHeight, skyBytes, compact);
        moments = GiProbeData.texture(visWidth, visHeight, visBytes, compact);
        metadata = GiProbeData.texture(4, count, descriptors, false);
    }

    // Shared binding contract for the incremental resident page pool.
    GiProbeSet(Texture2D sh, Texture2D moments, Texture2D sky, Texture2D metadata) {
        this.sh = sh;
        this.moments = moments;
        this.skyVisibility = sky;
        this.metadata = metadata;
        this.count = MAX_VOLUMES;
    }

    private static void copy(Image source, ByteBuffer target, int width, int row, boolean compact) {
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                for (int c = 0; c < 4; c++) {
                    GiProbeData.put(target, ((row + y) * width + x) * 4 + c,
                            GiProbeData.component(source, (y * source.getWidth() + x) * 4 + c), compact);
                }
            }
        }
    }

    public long getTextureBytes() {
        return (long) sh.getImage().getData(0).capacity() + moments.getImage().getData(0).capacity()
                + metadata.getImage().getData(0).capacity() + skyVisibility.getImage().getData(0).capacity();
    }

    public Binding bind(Material material, float normalBias) {
        if (closed || !Float.isFinite(normalBias) || normalBias < 0) {
            throw new IllegalStateException("Live set and nonnegative bias required");
        }
        return new Binding(material, normalBias);
    }

    /** Restores parameters present before binding. Do not nest bindings on a material. */
    public final class Binding implements AutoCloseable {
        private final String[] names = {"GiIrradiance", "GiMoments", "GiVolumeData", "GiVolumeCount",
            "GiTextureSize", "GiBias", "GiEnabled", "GiSkyVisibility"};
        private final MatParam[] previous = new MatParam[names.length];
        private Material material;

        private Binding(Material material, float bias) {
            for (int i = 0; i < names.length; i++) {
                if (material.getMaterialDef().getMaterialParam(names[i]) == null) {
                    throw new IllegalArgumentException("Missing GI parameter " + names[i]);
                }
                MatParam param = material.getParam(names[i]);
                previous[i] = param == null ? null : param.clone();
            }
            this.material = material;
            material.setTexture("GiIrradiance", sh);
            material.setTexture("GiMoments", moments);
            material.setTexture("GiSkyVisibility", skyVisibility);
            material.setTexture("GiVolumeData", metadata);
            material.setInt("GiVolumeCount", count);
            material.setVector4("GiTextureSize", new Vector4f(sh.getImage().getWidth(), sh.getImage().getHeight(),
                    moments.getImage().getWidth(), moments.getImage().getHeight()));
            material.setFloat("GiBias", bias);
            material.setBoolean("GiEnabled", true);
        }

        @Override
        public void close() {
            if (material == null) {
                return;
            }
            for (int i = 0; i < names.length; i++) {
                material.clearParam(names[i]);
                if (previous[i] instanceof MatParamTexture) {
                    material.setTexture(names[i], (Texture) previous[i].getValue());
                } else if (previous[i] != null) {
                    material.setParam(names[i], previous[i].getVarType(), previous[i].getValue());
                }
            }
            material = null;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            sh.getImage().dispose();
            skyVisibility.getImage().dispose();
            moments.getImage().dispose();
            metadata.getImage().dispose();
        }
    }
}
