package com.jme3.environment.gi;

import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.Savable;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import java.io.IOException;

/**
 * Marks the root of a standalone probe lightmap J3O and records its texture layout.
 * The same asset can be loaded by the runtime streamer or inspected without a scene.
 * <pre>{@code
 * // 第一步：使用 bakeField 生成线性 HDR 双区域贴图。
 * ProbeLightmapAsset asset = new ProbeLightmapAsset(texture, true);
 * // 第二步：保存独立资源，在场景里用 ProbeLightmapControl 引用路径。
 * new BinaryExporter().save(asset, lightmapFile);
 * // 第三步：SDK 根据 J3O 根类型打开 Lightmap 预览器。
 * }</pre>
 */
public final class ProbeLightmapAsset implements Savable {
    private static final int IRRADIANCE = 1;
    private static final int IRRADIANCE_AND_SKY = 2;
    private Texture2D texture;
    private int layout;

    public ProbeLightmapAsset() {
    }

    public ProbeLightmapAsset(Texture2D texture, boolean skyBand) {
        this.texture = texture;
        layout = skyBand ? IRRADIANCE_AND_SKY : IRRADIANCE;
        if (!isValid()) {
            throw new IllegalArgumentException("Invalid probe lightmap texture or layout");
        }
    }

    /** @return the resident HDR texture; RGB is irradiance and alpha is coverage in the first band */
    public Texture2D getTexture() {
        return texture;
    }

    /** @return whether the upper band stores signed bent direction and sky visibility */
    public boolean hasSkyBand() {
        return layout == IRRADIANCE_AND_SKY;
    }

    /** Accepts marked assets and historical bare RGBA16F texture assets from the same baker. */
    public static ProbeLightmapAsset fromSaved(Savable data) throws IOException {
        if (data instanceof ProbeLightmapAsset) {
            return (ProbeLightmapAsset) data;
        }
        if (data instanceof Texture2D) {
            try {
                return new ProbeLightmapAsset((Texture2D) data, false);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid legacy probe lightmap texture", invalid);
            }
        }
        throw new IOException("J3O does not contain a probe lightmap");
    }

    private boolean isValid() {
        Image image = texture == null ? null : texture.getImage();
        return (layout == IRRADIANCE || layout == IRRADIANCE_AND_SKY)
                && image != null && image.getFormat() == Image.Format.RGBA16F
                && image.getColorSpace() == ColorSpace.Linear
                && image.getWidth() >= 2 && image.getHeight() >= (hasSkyBand() ? 4 : 2)
                && (!hasSkyBand() || image.getHeight() % 2 == 0) && !image.getData().isEmpty()
                && image.getData(0) != null
                && image.getData(0).limit() >= (long) image.getWidth() * image.getHeight() * 8;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        if (!isValid()) {
            throw new IOException("Invalid probe lightmap asset");
        }
        var capsule = exporter.getCapsule(this);
        capsule.write(layout, "layout", 0);
        capsule.write(texture, "texture", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        var capsule = importer.getCapsule(this);
        layout = capsule.readInt("layout", 0);
        texture = (Texture2D) capsule.readSavable("texture", null);
        if (!isValid()) {
            throw new IOException("Unsupported probe lightmap layout or texture");
        }
    }
}
