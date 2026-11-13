package com.jme3.scene.plugins.sdf;

import com.jme3.export.InputCapsule;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.OutputCapsule;
import com.jme3.export.Savable;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture3D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Dense float distance samples in model-root local units. Negative means inside for
 * EVEN_ODD; UNSIGNED contains only non-negative values. Grid endpoints coincide with
 * minimum/maximum bounds, with X varying fastest: x + sizeX * (y + sizeY * z).
 * This Savable lives in jme3-plugins; loading a j3o containing it requires that module.
 * Material textures remain ordinary external j3o dependencies, not embedded by this API.
 * Treat the result as immutable; read() is reserved for deserialization.
 */
public final class SdfVolume implements Savable {
    /** Model user-data key used by attachTo; caller may choose another key instead. */
    public static final String USER_DATA_KEY = "jme.sdf.volume";
    private int x;
    private int y;
    private int z;
    private Vector3f min;
    private Vector3f max;
    private float[] distances;
    private SdfSettings.SignMode signMode;
    private String sourceGeometryHash;

    void setSourceGeometryHash(String hash) {
        sourceGeometryHash = hash;
    }

    /**
     * Root-local triangle source signature for compatibility with independent surface bakes.
     * Legacy files may return null. Does not encode UV/material values or SDF resolution.
     * @return geometry signature, or null for legacy data without source metadata
     */
    public String getSourceGeometryHash() {
        return sourceGeometryHash;
    }

    /** For BinaryImporter only; generate populated instances with SdfGenerator. */
    public SdfVolume() {
    }

    SdfVolume(SdfSettings settings, Vector3f min, Vector3f max, float[] values) {
        x = settings.x;
        y = settings.y;
        z = settings.z;
        this.min = min.clone();
        this.max = max.clone();
        distances = values;
        signMode = settings.signMode;
        validate();
    }

    /** @return X sample count */
    public int getSizeX() {
        return x;
    }
    /** @return Y sample count */
    public int getSizeY() {
        return y;
    }
    /** @return Z sample count */
    public int getSizeZ() {
        return z;
    }
    /** @return independent minimum bound in root-local coordinates */
    public Vector3f getMin() {
        return min.clone();
    }
    /** @return independent maximum bound in root-local coordinates */
    public Vector3f getMax() {
        return max.clone();
    }
    /** @return sign policy stored with this volume */
    public SdfSettings.SignMode getSignMode() {
        return signMode;
    }
    /** @return independent copy of X-fastest float data; potentially large */
    public float[] copyDistances() {
        return distances.clone();
    }

    /**
     * Returns a grid sample without interpolation.
     * <pre>{@code
     * // 查询中心附近的一个格点；负数代表闭合模型内部。
     * float d = sdf.getDistance(sdf.getSizeX() / 2, sdf.getSizeY() / 2, sdf.getSizeZ() / 2);
     * }</pre>
     * @param ix grid X index
     * @param iy grid Y index
     * @param iz grid Z index
     * @return sampled distance in root-local units
     */
    public float getDistance(int ix, int iy, int iz) {
        if (ix < 0 || iy < 0 || iz < 0 || ix >= x || iy >= y || iz >= z) {
            throw new IndexOutOfBoundsException("SDF grid coordinate out of range");
        }
        return distances[ix + x * (iy + y * iz)];
    }

    /**
     * Trilinear approximation inside the stored bounds; rejects outside/non-finite
     * points rather than silently clamping/extrapolating a misleading distance.
     * <pre>{@code
     * // 第一步：输入必须是生成时模型根节点的局部坐标。
     * Vector3f localPoint = model.worldToLocal(worldPoint, new Vector3f());
     * // 第二步：点在 sdf.getMin()/getMax() 内才能查询。
     * float distance = sdf.sample(localPoint);
     * // 模型整体缩放后此值仍是局部单位；非均匀缩放不能简单转成世界空间精确 SDF。
     * }</pre>
     * @param point root-local point
     * @return trilinearly interpolated distance, not an exact mesh query
     */
    public float sample(Vector3f point) {
        double gx = coordinate(point.x, min.x, max.x, x);
        double gy = coordinate(point.y, min.y, max.y, y);
        double gz = coordinate(point.z, min.z, max.z, z);
        int ix = Math.min(x - 2, (int) gx);
        int iy = Math.min(y - 2, (int) gy);
        int iz = Math.min(z - 2, (int) gz);
        double value = 0;
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    double weight = (dx == 0 ? 1 - (gx - ix) : gx - ix)
                            * (dy == 0 ? 1 - (gy - iy) : gy - iy)
                            * (dz == 0 ? 1 - (gz - iz) : gz - iz);
                    value += weight * getDistance(ix + dx, iy + dy, iz + dz);
                }
            }
        }
        return (float) value;
    }

    /**
     * @return root-local spacing between adjacent grid samples (not bounds/resolution)
     */
    public Vector3f getCellSize() {
        return new Vector3f((float) (((double) max.x - min.x) / (x - 1)),
                (float) (((double) max.y - min.y) / (y - 1)),
                (float) (((double) max.z - min.z) / (z - 1)));
    }

    /**
     * Conservative interpolation margin for a Euclidean mesh distance: half the
     * cell diagonal, plus a float precision allowance. Trilinear interpolation of
     * distance samples is not itself an exact distance and may overestimate.
     * <pre>{@code
     * // DDGI shader 使用同一个修正量，不能直接用 R32F 采样值当作绝对安全步长。
     * material.setFloat("SdfInterpolationMargin", sdf.getInterpolationMargin());
     * // step = max(abs(sampledDistance) - margin, 0.0)，单位为模型根节点局部单位。
     * }</pre>
     * @return positive local-space safety margin
     */
    public float getInterpolationMargin() {
        Vector3f cell = getCellSize();
        double coordinateScale = Math.max(Math.max(Math.abs(min.x), Math.abs(max.x)),
                Math.max(Math.max(Math.abs(min.y), Math.abs(max.y)), Math.max(Math.abs(min.z), Math.abs(max.z))));
        return (float) (.5 * Math.sqrt((double) cell.x * cell.x + (double) cell.y * cell.y
                + (double) cell.z * cell.z) + 16 * Math.ulp((float) coordinateScale));
    }

    /**
     * Non-negative conservative step toward the nearest captured triangle, obtained
     * by subtracting the interpolation margin from the interpolated magnitude.
     * A zero result means "within the grid uncertainty band", not an exact hit.
     * <pre>{@code
     * // 第一步：光线先与 SDF bounds 求交，查询点必须处于体积内。
     * float step = sdf.sampleConservativeDistance(localRayPoint);
     * // 第二步：step > 0 可沿单位长度的局部方向推进；禁止强行抬高最小步长。
     * // step == 0 时需细化/三角形检测或明确的近似命中策略；否则仍可能漏薄墙。
     * // 非均匀世界缩放需要相应变换光线和步长，不能直接拿局部距离作为世界距离。
     * }</pre>
     * @param point root-local query point inside bounds
     * @return local-space step lower bound, zero in the uncertainty band
     */
    public float sampleConservativeDistance(Vector3f point) {
        return Math.max(0, Math.abs(sample(point)) - getInterpolationMargin());
    }

    private static double coordinate(float p, float min, float max, int count) {
        if (!Float.isFinite(p) || p < min || p > max) {
            throw new IllegalArgumentException("Sample point outside SDF bounds");
        }
        return ((double) p - min) / ((double) max - min) * (count - 1);
    }

    /**
     * Attaches this result to its original model root, replacing any previous SDF
     * at USER_DATA_KEY. Scene changes require rebaking; no automatic invalidation.
     * Call on the scene-owning thread after a background bake has succeeded.
     * <pre>{@code
     * // 第一步：生成成功后才修改模型，取消/失败不覆盖旧结果。
     * sdf.attachTo(model);
     * // 第二步：正常保存模型；SDF float 数据和空间元信息随 j3o 一起写入。
     * BinaryExporter.getInstance().save(model, new File("MyModel-sdf.j3o"));
     * // 第三步：正常加载，从根节点取回，不会再次生成。
     * Spatial loaded = assetManager.loadModel("MyModel-sdf.j3o");
     * SdfVolume restored = loaded.getUserData(SdfVolume.USER_DATA_KEY);
     * // 删除结果：model.setUserData(SdfVolume.USER_DATA_KEY, null)。
     * }</pre>
     * @param model the same root/coordinate system used for generation
     */
    public void attachTo(Spatial model) {
        model.setUserData(USER_DATA_KEY, this);
    }

    /**
     * Builds a CPU-side R32F Texture3D; GPU upload remains renderer-managed. Caller
     * owns the texture. No graphics context is needed here; no mipmaps are generated.
     * <pre>{@code
     * // 按需创建一次，不要每帧复制整份数据。
     * Texture3D texture = sdf.toTexture3D();
     * material.setTexture("DistanceField", texture);
     * // 格点坐标 i 对应纹理中心 (i + 0.5) / resolution；边界是首末采样点位置。
     * // shader 坐标：(local-min)/(max-min)*(resolution-1)/resolution + 0.5/resolution。
     * }</pre>
     * @return independent single-channel 3D texture, hardware format support required on upload
     */
    public Texture3D toTexture3D() {
        ByteBuffer bytes = BufferUtils.createByteBuffer(distances.length * 4);
        bytes.asFloatBuffer().put(distances);
        ArrayList<ByteBuffer> data = new ArrayList<>();
        data.add(bytes);
        Texture3D texture = new Texture3D(new Image(Image.Format.R32F, x, y, z, data, ColorSpace.Linear));
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
        return texture;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule c = exporter.getCapsule(this);
        c.write(2, "version", 0);
        c.write(sourceGeometryHash, "sourceGeometryHash", null);
        c.write(x, "sizeX", 0);
        c.write(y, "sizeY", 0);
        c.write(z, "sizeZ", 0);
        c.write(min, "min", null);
        c.write(max, "max", null);
        c.write(signMode, "signMode", SdfSettings.SignMode.EVEN_ODD);
        c.write(distances, "distances", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule c = importer.getCapsule(this);
        int version = c.readInt("version", 0);
        if (version != 1 && version != 2) {
            throw new IOException("Unsupported SDF data version");
        }
        sourceGeometryHash = c.readString("sourceGeometryHash", null);
        x = c.readInt("sizeX", 0);
        y = c.readInt("sizeY", 0);
        z = c.readInt("sizeZ", 0);
        min = (Vector3f) c.readSavable("min", null);
        max = (Vector3f) c.readSavable("max", null);
        signMode = c.readEnum("signMode", SdfSettings.SignMode.class, SdfSettings.SignMode.EVEN_ODD);
        distances = c.readFloatArray("distances", null);
        try {
            validate();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid SDF data", e);
        }
    }

    private void validate() {
        int count = SdfSettings.sampleCount(x, y, z);
        if (min == null || max == null || signMode == null || distances == null || distances.length != count) {
            throw new IllegalArgumentException("Invalid SDF metadata");
        }
        for (int axis = 0; axis < 3; axis++) {
            if (!Float.isFinite(min.get(axis)) || !Float.isFinite(max.get(axis)) || min.get(axis) >= max.get(axis)) {
                throw new IllegalArgumentException("Invalid SDF bounds");
            }
        }
        for (float distance : distances) {
            if (!Float.isFinite(distance)) {
                throw new IllegalArgumentException("Non-finite SDF sample");
            }
        }
    }
}
