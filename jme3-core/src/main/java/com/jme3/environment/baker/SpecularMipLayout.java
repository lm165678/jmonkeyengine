package com.jme3.environment.baker;

import com.jme3.texture.Image;
import com.jme3.texture.TextureCubeMap;
import java.nio.ByteBuffer;
import java.util.List;

/** Internal byte layout shared by the specular IBL bakers and light importers. */
public final class SpecularMipLayout {
    private static final int LEGACY_MAX_LEVELS = 6;

    private SpecularMipLayout() {
    }

    static int[] sizes(Image image, int levels) {
        int width = image.getWidth();
        int height = image.getHeight();
        int bytesPerPixel = image.getFormat().getBitsPerPixel() / Byte.SIZE;
        int[] sizes = new int[levels];
        for (int level = 0; level < levels; level++) {
            sizes[level] = Math.toIntExact((long) width * height * bytesPerPixel);
            width = Math.max(1, width >> 1);
            height = Math.max(1, height >> 1);
        }
        return sizes;
    }

    /**
     * Repairs the historical GPU bake layout during light import, before
     * the renderer uploads the map. The old baker sized the table from the
     * capped mip count but pulled pixels from the actual cube dimensions.
     * Only matching tables with a complete set of face payloads are repaired.
     *
     * @param map the deserialized prefiltered cubemap, or null
     */
    public static void repairLegacy(TextureCubeMap map) {
        Image image = map == null ? null : map.getImage();
        if (image == null) {
            return;
        }
        int[] storedSizes = image.getMipMapSizes();
        Image.Format format = image.getFormat();
        int width = image.getWidth();
        if (storedSizes == null || storedSizes.length < 2 || format == null
                || format.isCompressed() || format.isDepthFormat()
                || width < 1 || width != image.getHeight()) {
            return;
        }

        int bitsPerPixel = format.getBitsPerPixel();
        int bytesPerPixel = bitsPerPixel / Byte.SIZE;
        if (bytesPerPixel == 0 || bitsPerPixel % Byte.SIZE != 0
                || (long) width * width > Integer.MAX_VALUE / bytesPerPixel) {
            return;
        }
        int legacyLevels = Math.min(LEGACY_MAX_LEVELS, Integer.SIZE - Integer.numberOfLeadingZeros(width));
        int legacyWidth = 1 << (legacyLevels - 1);
        if (legacyWidth == width || storedSizes.length > legacyLevels) {
            return;
        }
        for (int size : storedSizes) {
            if (size != legacyWidth * legacyWidth * bytesPerPixel) {
                return;
            }
            legacyWidth >>= 1;
        }

        List<ByteBuffer> faces = image.getData();
        if (faces == null || faces.size() != 6) {
            return;
        }
        int[] correctedSizes = sizes(image, storedSizes.length);
        long faceSize = 0;
        for (int size : correctedSizes) {
            faceSize += size;
        }
        for (ByteBuffer face : faces) {
            if (face == null || face.capacity() != faceSize) {
                return;
            }
        }
        image.setMipMapSizes(correctedSizes);
        image.setMipmapsGenerated(true);
    }
}
