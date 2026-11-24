package com.jme3.environment.gi;

import com.jme3.texture.Image;
import java.nio.ByteBuffer;

/** Runtime-only, seam-padded moments for a single hardware bilinear lookup. */
final class GiLinearMoments {
    private GiLinearMoments() {
    }

    static int width(GiProbeData data) {
        return data.columns() * (data.resolution() + 2);
    }

    static int height(GiProbeData data) {
        return data.moments().getImage().getHeight() / data.resolution() * (data.resolution() + 2);
    }

    static void copyRow(GiProbeData data, int sourceRow, ByteBuffer target, int width, int row) {
        Image source = data.moments().getImage();
        int size = data.resolution();
        int stride = size + 2;
        int tileY = sourceRow / stride;
        int py = sourceRow % stride - 1;
        for (int x = 0; x < width(data); x++) {
            int tileX = x / stride;
            int px = x % stride - 1;
            int sx = px;
            int sy = py;
            // Match giWrapMomentPixel, including the four octahedral corners.
            if (sx < 0) {
                sx = -sx - 1;
                sy = size - 1 - sy;
            }
            if (sx >= size) {
                sx = 2 * size - 1 - sx;
                sy = size - 1 - sy;
            }
            if (sy < 0) {
                sy = -sy - 1;
                sx = size - 1 - sx;
            }
            if (sy >= size) {
                sy = 2 * size - 1 - sy;
                sx = size - 1 - sx;
            }
            int index = ((tileY * size + sy) * source.getWidth() + tileX * size + sx) * 4;
            float mean = GiProbeData.component(source, index);
            float second = GiProbeData.component(source, index + 1);
            float deviation = GiProbeData.component(source, index + 2);
            float flag = GiProbeData.component(source, index + 3);
            double variance = flag > 0.5f ? (double) deviation * deviation
                    : Math.max(0.0, (double) second - (double) mean * mean);
            int offset = ((row * width + x) * 4) * 4;
            target.putFloat(offset, mean);
            target.putFloat(offset + 4, (float) ((double) mean * mean + variance));
            // An independent lower bound preserves small per-texel variance when
            // subtracting filtered second moments would cancel it in float32.
            target.putFloat(offset + 8, (float) variance);
            target.putFloat(offset + 12, 0);
        }
    }
}
