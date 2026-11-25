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
    private static final int LEGACY_DIRECT_BANDS = 1;
    private static final int DIRECTIONAL_DIRECT_BANDS = 2;
    private Texture2D texture;
    private Texture2D direct;
    private String[] directShadowIds;
    private int directBandsVersion;
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

    /**
     * Packs up to four maps from {@link ProbeLightmapBaker#bakeDirect} in vertical
     * bands, in matching light order. RGB stores light color times static visibility
     * divided by pi; the surface normal is applied in the material shader.
     * Alpha records coverage. Each band is sampled at base level
     * with bilinear filtering clamped within the band. See {@link BakedDirectionalShadow}.
     */
    public static ProbeLightmapAsset withDirect(Texture2D indirect, boolean skyBand,
            java.util.List<Texture2D> maps, java.util.List<BakedDirectionalShadow> shadows) {
        ProbeLightmapAsset result = new ProbeLightmapAsset(indirect, skyBand);
        result.directBandsVersion = DIRECTIONAL_DIRECT_BANDS;
        if (maps.isEmpty() || maps.size() > BakedDirectionalShadow.MAX_LIGHTS || maps.size() != shadows.size()) {
            throw new IllegalArgumentException("Expected one direct map per baked light");
        }
        int width = indirect.getImage().getWidth();
        int height = indirect.getImage().getHeight() / (skyBand ? 2 : 1);
        int stride = width * height * 8;
        java.nio.ByteBuffer pixels = com.jme3.util.BufferUtils.createByteBuffer(stride * maps.size());
        result.directShadowIds = new String[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            Image image = maps.get(i).getImage();
            if (image.getFormat() != Image.Format.RGBA16F || image.getWidth() != width || image.getHeight() != height) {
                throw new IllegalArgumentException("Direct Lightmap bands must match the indirect atlas dimensions");
            }
            java.nio.ByteBuffer source = image.getData(0).duplicate();
            source.position(0);
            source.limit(stride);
            pixels.put(source);
            result.directShadowIds[i] = shadows.get(i).getId();
        }
        pixels.flip();
        result.direct = new Texture2D(new Image(Image.Format.RGBA16F, width, height * maps.size(),
                pixels, ColorSpace.Linear));
        result.direct.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        result.direct.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
        result.direct.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        return result;
    }

    /** @return the optional direct diffuse atlas */
    public Texture2D getDirectTexture() {
        return direct;
    }

    /** @return true when direct bands need the current shading normal at playback */
    public boolean usesDirectionalDirectBands() {
        return directBandsVersion == DIRECTIONAL_DIRECT_BANDS;
    }

    /** @return identifiers in direct atlas band order */
    public String[] getDirectShadowIds() {
        return directShadowIds == null ? null : directShadowIds.clone();
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
                && image.getData(0).limit() >= (long) image.getWidth() * image.getHeight() * 8 && validDirect(image);
    }

    private boolean validDirect(Image indirect) {
        if (direct == null) {
            return directShadowIds == null;
        }
        Image image = direct.getImage();
        return directShadowIds != null && directShadowIds.length > 0
                && directShadowIds.length <= BakedDirectionalShadow.MAX_LIGHTS && image != null
                && image.getFormat() == Image.Format.RGBA16F && image.getColorSpace() == ColorSpace.Linear
                && image.getWidth() == indirect.getWidth()
                && image.getHeight() == indirect.getHeight() / (hasSkyBand() ? 2 : 1) * directShadowIds.length
                && !image.getData().isEmpty() && image.getData(0) != null
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
        capsule.write(direct, "direct", null);
        capsule.write(directShadowIds, "directShadowIds", null);
        capsule.write(direct == null ? 0 : directBandsVersion, "directBandsVersion", 0);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        var capsule = importer.getCapsule(this);
        layout = capsule.readInt("layout", 0);
        texture = (Texture2D) capsule.readSavable("texture", null);
        direct = (Texture2D) capsule.readSavable("direct", null);
        directShadowIds = capsule.readStringArray("directShadowIds", null);
        directBandsVersion = capsule.readInt("directBandsVersion", 0);
        if (directBandsVersion != LEGACY_DIRECT_BANDS && directBandsVersion != DIRECTIONAL_DIRECT_BANDS) {
            direct = null;
            directShadowIds = null;
        }
        if (!isValid()) {
            throw new IOException("Unsupported probe lightmap layout or texture");
        }
    }
}
