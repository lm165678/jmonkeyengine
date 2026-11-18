package com.jme3.environment.gi;

import com.jme3.export.*;
import com.jme3.material.Material;
import com.jme3.material.MatParam;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * <p>New bakes store distance mean/second moment in R/G and independent standard
 * deviation in B (A=1). Readers prefer B to avoid half-float cancellation. Old
 * assets with A=0 use the legacy R/G decoder; rebake to recover lost precision.
 * The extra channels already existed, so texture dimensions and memory stay
 * unchanged. Do not edit only R/G of a new bake without updating B/A.</p>
 *

 * <p>SimpleApplication automatically manages attached GiProbeVolume nodes through
 * SceneServicesState. For explicit low-level bindings/streaming as below, call
 * {@code app.getSceneServices().setEnabled(false)} before installing manual
 * bindings. Close manual bindings before enabling automatic services again.
 * Standalone data not attached as a volume is unaffected.</p>
 *

 * Serializable resident GI chunk: SH9 irradiance and octahedral distance moments.
 * Default normalized half storage uses 72 bytes/probe for SH9 and
 * 8*resolution^2 bytes/probe for visibility, excluding atlas padding. Full-float
 * storage doubles these figures. Validity and relocation share SH alpha channels.
 * Separated sky adds 72 bytes/probe in compact storage for sky visibility SH.
 * It does not store sky radiance twice. Legacy total-lighting assets remain
 * supported, but need rebaking to acquire separated sky visibility.
 * No cubemaps survive baking. Split a world into bounded chunks and
 * load/unload these assets with the corresponding geometry. Neighboring chunks
 * should share their boundary probe positions and capture the same surroundings.
 *
 * <p>Binding is explicit and material-owned, so instancing/GpuScene retains its
 * normal material grouping. bind() attaches one chunk; use GiProbeSet or
 * GiProbeStreamer to blend multiple chunks on shared materials. No render path
 * or SDF state is changed. Outside
 * coverage the contribution is zero. Missing data is represented by no binding.
 * Positive visibility weights are normalized relative to their largest weight;
 * low confidence alone does not abruptly discard irradiance. Exactly occluded
 * or invalid neighbors still contribute zero. This sampling correction applies
 * to existing baked assets without rebaking, but cannot recover light absent
 * from captures or add probes to undersampled rooms.
 *
 * <pre>{@code
 * // 第一步：在渲染线程读取已烘焙分块（BinaryExporter/Importer 支持此资源）。
 * GiProbeData data = volume.getData();
 * // 第二步：绑定到内置 PBR、Lighting 或 Unshaded 材质。
 * GiProbeData.Binding binding = data.bind(material, volume.getWorldTranslation(), 0.05f);
 * // 第三步：单体积边界默认按 1 个世界单位渐变，可按场景尺度调整。
 * material.setFloat("GiFadeDistance", 2f);
 * // 第四步：卸载分块或恢复原材质参数；先解绑，再释放资源。
 * binding.close();
 * // 第五步：在工作线程压缩存储；不要同时释放或修改此数据。
 * // try (OutputStream out = Files.newOutputStream(path)) {
 * //     data.saveCompressed(out);
 * // }
 * // 加载同样可在工作线程执行；材质绑定和释放必须回到渲染线程。
 * }</pre>
 * Receivers need position and normal vertex attributes; normal-mapped materials
 * also need tangents. Bind/close and image disposal must run on the render thread, outside drawing.
 * Do not overlap bindings on the same material. Unshaded explicitly opts into
 * additive diffuse GI on binding; its unlit base is retained. Without a binding
 * its original unlit behavior remains.
 * Distance bias uses world units and offsets both visibility queries and the
 * interpolation cell; coverage still uses the unmodified surface. Zero disables
 * the offset. Invalid/fully occluded overlapping grids do not override valid
 * coarse-grid lighting. Coefficients contain irradiance, not irradiance/pi.
 */
public final class GiProbeData implements Savable {
    private Vector3f origin;
    private Vector3f spacing;
    private Vector3f counts;
    private Texture2D irradiance;
    private Texture2D moments;
    private Texture2D skyVisibility;
    private int resolution;
    private boolean surfaceLayout;
    private int columns;
    private float distanceScale = 1;
    private float irradianceScale = 1;

    /** Serialization constructor. Use the scene baker to create valid data. */
    public GiProbeData() {
    }

    GiProbeData(GiProbeGrid grid, int resolution, float[][] sh, float[][] distances) {
        this(grid, resolution, sh, distances, null, null, false, 1);
    }

    GiProbeData(GiProbeGrid grid, int resolution, float[][] sh, float[][] distances,
            Vector3f[] offsets, boolean[] valid, boolean compact, float maxDistance) {
        this(grid, resolution, sh, distances, offsets, valid, compact, maxDistance, null);
    }

    GiProbeData(GiProbeGrid grid, int resolution, float[][] sh, float[][] distances,
            Vector3f[] offsets, boolean[] valid, boolean compact, float maxDistance, float[][] skySh) {
        origin = grid.getOrigin();
        spacing = grid.getSpacing();
        counts = grid.getCounts();
        this.resolution = resolution;
        int probes = grid.getProbeCount();
        columns = (int) Math.ceil(Math.sqrt(probes));
        int rows = (probes + columns - 1) / columns;
        int bytesPerPixel = compact ? 8 : 16;
        if (compact) {
            distanceScale = maxDistance;
            for (float[] probe : sh) {
                for (float coefficient : probe) {
                    irradianceScale = Math.max(irradianceScale, Math.abs(coefficient));
                }
            }
        }
        ByteBuffer coefficients = BufferUtils.createByteBuffer(columns * 9 * rows * bytesPerPixel);
        ByteBuffer visibility = BufferUtils.createByteBuffer(columns * rows * resolution * resolution * bytesPerPixel);
        for (int p = 0; p < probes; p++) {
            for (int c = 0; c < 9; c++) {
                int pixel = (p / columns * columns * 9 + p % columns * 9 + c) * 4;
                for (int channel = 0; channel < 3; channel++) {
                    put(coefficients, pixel + channel, sh[p][c * 3 + channel] / irradianceScale, compact);
                }
                float alpha = c == 0 && (valid == null || valid[p]) ? 1 : 0;
                if (offsets != null && offsets[p] != null && c >= 1 && c <= 3) {
                    alpha = offsets[p].get(c - 1) / spacing.get(c - 1);
                }
                put(coefficients, pixel + 3, alpha, compact);
            }
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    int pixel = ((p / columns * resolution + y) * columns * resolution
                            + p % columns * resolution + x) * 4;
                    int source = (y * resolution + x) * 2;
                    put(visibility, pixel, distances[p][source] / distanceScale, compact);
                    put(visibility, pixel + 1, distances[p][source + 1] / distanceScale / distanceScale, compact);
                    // Keep R/G for legacy readers. Independent standard deviation
                    // avoids catastrophic E[d*d]-E[d]^2 cancellation in half floats.
                    // Square root also preserves small normalized variances that
                    // would underflow when stored directly in an RGBA16F channel.
                    double mean = distances[p][source];
                    double variance = Math.max(0.0, (double) distances[p][source + 1] - mean * mean);
                    put(visibility, pixel + 2, (float) (Math.sqrt(variance) / distanceScale), compact);
                    put(visibility, pixel + 3, 1f, compact);
                }
            }
        }
        irradiance = texture(columns * 9, rows, coefficients, compact);
        moments = texture(columns * resolution, rows * resolution, visibility, compact);
        if (skySh != null) {
            ByteBuffer skyBytes = BufferUtils.createByteBuffer(coefficients.capacity());
            for (int p = 0; p < probes; p++) {
                for (int c = 0; c < 9; c++) {
                    int pixel = (p / columns * columns * 9 + p % columns * 9 + c) * 4;
                    for (int channel = 0; channel < 3; channel++) {
                        put(skyBytes, pixel + channel, skySh[p][c * 3 + channel], compact);
                    }
                }
            }
            skyVisibility = texture(columns * 9, rows, skyBytes, compact);
        }
    }

    /** True for free surface positions with spatial-bin neighbor lookup. */
    public boolean isSurfaceLayout() {
        return surfaceLayout;
    }

    // Bin heads and next links occupy reserved coefficient alpha channels 4/5.
    // Full-float storage preserves integer indices and arbitrary position offsets.
    void enableSurfaceLayout() {
        if (irradiance.getImage().getFormat() != Image.Format.RGBA32F) {
            throw new IllegalArgumentException("Surface indices require full precision");
        }
        surfaceLayout = true;
        int count = getGrid().getProbeCount();
        int[] heads = new int[count];
        int[] sizes = new int[count];
        java.util.Arrays.fill(heads, -1);
        ByteBuffer bytes = irradiance.getImage().getData(0);
        Vector3f point = new Vector3f();
        for (int i = 0; i < count; i++) {
            bytes.putFloat((probeComponent(i, 5) + 3) * 4, -1);
            if (!isProbeValid(i)) {
                continue;
            }
            getGrid().position(i, point);
            point.addLocal(getProbeOffset(i, new Vector3f())).subtractLocal(origin);
            int bin = GiSurfaceProbeLayout.bin(point, spacing, counts);
            if (bin < 0 || ++sizes[bin] > GiSurfaceProbeLayout.BIN_CAPACITY) {
                throw new IllegalArgumentException("Invalid surface probe bin or capacity");
            }
            bytes.putFloat((probeComponent(i, 5) + 3) * 4, heads[bin]);
            heads[bin] = i;
        }
        for (int i = 0; i < count; i++) {
            bytes.putFloat((probeComponent(i, 4) + 3) * 4, heads[i]);
        }
        irradiance.getImage().setUpdateNeeded();
    }

    static Texture2D texture(int width, int height, ByteBuffer bytes, boolean compact) {
        Texture2D texture = new Texture2D(new Image(compact ? Image.Format.RGBA16F : Image.Format.RGBA32F,
                width, height, bytes, ColorSpace.Linear));
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        return texture;
    }

    static void put(ByteBuffer target, int component, float value, boolean compact) {
        if (compact) {
            target.putShort(component * 2, com.jme3.math.FastMath.convertFloatToHalf(value));
        } else {
            target.putFloat(component * 4, value);
        }
    }

    static float component(Image image, int index) {
        return image.getFormat() == Image.Format.RGBA16F
                ? com.jme3.math.FastMath.convertHalfToFloat(image.getData(0).getShort(index * 2))
                : image.getData(0).getFloat(index * 4);
    }

    Texture2D irradiance() {
        return irradiance;
    }

    Texture2D moments() {
        return moments;
    }

    Texture2D skyVisibility() {
        return skyVisibility;
    }

    /** True for separated bounce radiance + sky visibility; old assets remain total lighting. */
    public boolean hasSeparatedSky() {
        return skyVisibility != null;
    }

    /** Irradiance composition contract; visibility contains no sky radiance. */
    public enum LightingContent {
        /** Total bake includes direct sky diffuse; suppress runtime sky in coverage. */
        TOTAL_IRRADIANCE,
        /** Surface radiance and a separate visibility field; add visible runtime sky once. */
        BOUNCED_IRRADIANCE_WITH_SKY_VISIBILITY
    }

    /**
     * Returns the existing format's semantics without converting any coefficients.
     * New bakes default to total irradiance, as do legacy data. Rebuild using
     * separateSky=true to opt into separate visibility. Query after bake/load on the owner thread.
     * <pre>{@code
     * // 第一步：载入或完成烘焙后检查格式。
     * GiProbeData.LightingContent content = data.getLightingContent();
     * // 第二步：使用 data.bind 或自动场景服务；合成方式由格式自动选择。
     * // 第三步：旧数据必须重新烘焙才能分离；不能修改标签假装完成转换。
     * }</pre>
     * @return explicit composition semantics
     */
    public LightingContent getLightingContent() {
        return hasSeparatedSky() ? LightingContent.BOUNCED_IRRADIANCE_WITH_SKY_VISIBILITY
                : LightingContent.TOTAL_IRRADIANCE;
    }

    int columns() {
        return columns;
    }

    int resolution() {
        return resolution;
    }

    float distanceScale() {
        return distanceScale;
    }

    float irradianceScale() {
        return irradianceScale;
    }

    /** Logical uncompressed texture bytes; drivers/bindless caches can keep extra copies. */
    public long getTextureBytes() {
        return (long) irradiance.getImage().getData(0).capacity() + moments.getImage().getData(0).capacity()
                + (skyVisibility == null ? 0 : skyVisibility.getImage().getData(0).capacity());
    }

    private int probeComponent(int probe, int coefficient) {
        if (probe < 0 || probe >= getGrid().getProbeCount()) {
            throw new IndexOutOfBoundsException("Probe outside volume");
        }
        return (probe / columns * columns * 9 + probe % columns * 9 + coefficient) * 4;
    }

    /** Diagnostic validity flag; disabled probes never contribute to runtime interpolation. */
    public boolean isProbeValid(int probe) {
        return component(irradiance.getImage(), probeComponent(probe, 0) + 3) >= 0.5f;
    }

    /** Writes the baked offset in volume-local world units. For surface layouts
     * this is the free position minus its storage-slot lattice position; it is
     * not a bounded relocation vector and must not be drawn as one. */
    public Vector3f getProbeOffset(int probe, Vector3f store) {
        return store.set(component(irradiance.getImage(), probeComponent(probe, 1) + 3) * spacing.x,
                component(irradiance.getImage(), probeComponent(probe, 2) + 3) * spacing.y,
                component(irradiance.getImage(), probeComponent(probe, 3) + 3) * spacing.z);
    }

    /** CPU diagnostic SH evaluation without neighbor blending, matching the shader's SH convention. */
    public Vector3f evaluateProbeIrradiance(int probe, Vector3f normal, Vector3f store) {
        if (!Vector3f.isValidVector(normal) || normal.lengthSquared() < 1e-12f) {
            throw new IllegalArgumentException("Nonzero finite normal required");
        }
        store.set(0, 0, 0);
        if (!isProbeValid(probe)) {
            return store;
        }
        double[] basis = new double[9];
        GiProbeBaker.basis(normal.normalize(), basis);
        for (int c = 0; c < 9; c++) {
            int index = probeComponent(probe, c);
            store.x += component(irradiance.getImage(), index) * basis[c] * irradianceScale;
            store.y += component(irradiance.getImage(), index + 1) * basis[c] * irradianceScale;
            store.z += component(irradiance.getImage(), index + 2) * basis[c] * irradianceScale;
        }
        return store.set(Math.max(0, store.x), Math.max(0, store.y), Math.max(0, store.z));
    }

    /** Saves a gzip-compressed binary asset. Worker-safe when the data is not modified. */
    public void saveCompressed(java.io.OutputStream output) throws IOException {
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(output);
        com.jme3.export.binary.BinaryExporter.getInstance().save(this, gzip);
        gzip.finish();
    }

    /** Loads a compressed chunk on a worker; does not upload GPU resources or close the input. */
    public static GiProbeData loadCompressed(java.io.InputStream input) throws IOException {
        return (GiProbeData) new com.jme3.export.binary.BinaryImporter().load(new java.util.zip.GZIPInputStream(input));
    }

    /** Returns a new descriptor; its coordinates remain relative to the volume. */
    public GiProbeGrid getGrid() {
        return new GiProbeGrid(origin, spacing, (int) counts.x, (int) counts.y, (int) counts.z);
    }

    /** Binds this resident chunk. Save the returned handle to restore previous state. */
    public Binding bind(Material material, Vector3f worldOffset, float normalBias) {
        if (!Vector3f.isValidVector(worldOffset) || !Float.isFinite(normalBias) || normalBias < 0) {
            throw new IllegalArgumentException("Finite offset and nonnegative bias required");
        }
        return new Binding(material, worldOffset, normalBias);
    }

    /** Releases GPU image storage after all bindings are closed; CPU data is retained. */
    public void dispose() {
        irradiance.getImage().dispose();
        moments.getImage().dispose();
        if (skyVisibility != null) {
            skyVisibility.getImage().dispose();
        }
    }

    /** Render-thread restoration handle. Closing twice is harmless. */
    public final class Binding implements AutoCloseable {
        private final String[] names = {"GiIrradiance", "GiMoments", "GiOrigin", "GiSpacing",
            "GiCounts", "GiAtlas", "GiBias", "GiEnabled", "GiEncoding", "GiVolumeCount", "GiSkyVisibility"};
        private final MatParam[] previous = new MatParam[names.length];
        private Material material;

        private Binding(Material material, Vector3f offset, float bias) {
            for (int i = 0; i < names.length; i++) {
                if (material.getMaterialDef().getMaterialParam(names[i]) == null) {
                    throw new IllegalArgumentException("Material does not declare GI parameter " + names[i]);
                }
                previous[i] = material.getParam(names[i]);
                if (previous[i] != null) {
                    previous[i] = previous[i].clone();
                }
            }
            this.material = material;
            material.setTexture("GiIrradiance", irradiance);
            material.setTexture("GiMoments", moments);
            material.setTexture("GiSkyVisibility", skyVisibility != null ? skyVisibility : irradiance);
            material.setVector3("GiOrigin", origin.add(offset));
            material.setVector3("GiSpacing", spacing.clone());
            material.setVector3("GiCounts", counts.clone());
            material.setVector4("GiAtlas", new Vector4f(columns, irradiance.getImage().getHeight(), resolution + (hasSeparatedSky() ? 0.5f : 0f) + (surfaceLayout ? 0.0625f : 0f), 1));
            material.setVector2("GiEncoding", new com.jme3.math.Vector2f(distanceScale, irradianceScale));
            material.setInt("GiVolumeCount", 0);
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
                if (previous[i] != null) {
                    if (previous[i] instanceof com.jme3.material.MatParamTexture) {
                        material.setTexture(names[i], (Texture) previous[i].getValue());
                    } else {
                        material.setParam(names[i], previous[i].getVarType(), previous[i].getValue());
                    }
                }
            }
            material = null;
        }
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule out = exporter.getCapsule(this);
        out.write(origin, "origin", null);
        out.write(spacing, "spacing", null);
        out.write(counts, "counts", null);
        out.write(irradiance, "irradiance", null);
        out.write(moments, "moments", null);
        out.write(skyVisibility, "skyVisibility", null);
        out.write(resolution, "resolution", 0);
        out.write(columns, "columns", 0);
        out.write(surfaceLayout ? 4 : hasSeparatedSky() ? 3 : 2, "giFormatVersion", 0);
        out.write(distanceScale, "distanceScale", 1);
        out.write(irradianceScale, "irradianceScale", 1);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule in = importer.getCapsule(this);
        origin = (Vector3f) in.readSavable("origin", null);
        spacing = (Vector3f) in.readSavable("spacing", null);
        counts = (Vector3f) in.readSavable("counts", null);
        irradiance = (Texture2D) in.readSavable("irradiance", null);
        moments = (Texture2D) in.readSavable("moments", null);
        skyVisibility = (Texture2D) in.readSavable("skyVisibility", null);
        resolution = in.readInt("resolution", 0);
        columns = in.readInt("columns", 0);
        int version = in.readInt("giFormatVersion", 0);
        if (version != 0 && version != 2 && version != 3 && version != 4) {
            throw new IOException("Unsupported GI chunk version " + version);
        }
        distanceScale = in.readFloat("distanceScale", 1);
        irradianceScale = in.readFloat("irradianceScale", 1);
        if (!Float.isFinite(distanceScale) || distanceScale <= 0
                || !Float.isFinite(irradianceScale) || irradianceScale <= 0) {
            throw new IOException("Invalid GI encoding scales");
        }
        if (origin == null || spacing == null || counts == null || irradiance == null
                || moments == null || resolution < 2 || columns < 1) {
            throw new IOException("Incomplete GI probe asset");
        }
        try {
            GiProbeGrid grid = getGrid();
            int expectedColumns = (int) Math.ceil(Math.sqrt(grid.getProbeCount()));
            int rows = (grid.getProbeCount() + expectedColumns - 1) / expectedColumns;
            if (!counts.equals(grid.getCounts()) || resolution > 64 || columns != expectedColumns
                    || grid.getProbeCount() > 65536) {
                throw new IOException("Invalid GI probe atlas layout");
            }
            validateTexture(irradiance, columns * 9, rows);
            if (version == 3 || skyVisibility != null) {
                if (skyVisibility == null) {
                    throw new IOException("Missing separated sky visibility");
                }
                validateTexture(skyVisibility, columns * 9, rows);
                skyVisibility.getImage().getData(0).order(ByteOrder.nativeOrder());
            }
            validateTexture(moments, columns * resolution, rows * resolution);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid GI grid", exception);
        }
        // Serialized ByteBuffers carry native float data. BinaryImporter retains order.
        irradiance.getImage().getData(0).order(ByteOrder.nativeOrder());
        moments.getImage().getData(0).order(ByteOrder.nativeOrder());
        if (version == 4) {
            try {
                enableSurfaceLayout();
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid surface probe layout", exception);
            }
        }
        if (version == 0) {
            if (irradiance.getImage().getFormat() != Image.Format.RGBA32F) {
                throw new IOException("Legacy GI assets require full-float irradiance");
            }
            // Legacy atlases reserved all alpha channels as zero and had no invalid probes.
            for (int p = 0; p < getGrid().getProbeCount(); p++) {
                irradiance.getImage().getData(0).putFloat((p / columns * columns * 9 + p % columns * 9) * 16 + 12, 1);
            }
        }
    }

    private static void validateTexture(Texture2D texture, int width, int height) throws IOException {
        Image image = texture.getImage();
        if (image == null || (image.getFormat() != Image.Format.RGBA32F && image.getFormat() != Image.Format.RGBA16F)
                || image.getWidth() != width || image.getHeight() != height
                || image.getData().size() != 1 || image.getData(0) == null
                || image.getData(0).capacity() != (long) width * height * (image.getFormat() == Image.Format.RGBA16F ? 8 : 16)) {
            throw new IOException("Invalid GI float atlas payload");
        }
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }
}
