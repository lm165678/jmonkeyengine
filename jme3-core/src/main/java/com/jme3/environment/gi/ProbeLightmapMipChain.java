package com.jme3.environment.gi;

import com.jme3.math.FastMath;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/** Encodes only the mip levels that can be filtered without crossing a UV2 chart. */
final class ProbeLightmapMipChain {
    private static final int CHANNELS = 4;
    private static final int TEXEL_BYTES = CHANNELS * Short.BYTES;
    // Bilinear taps have support one texel from their center on each axis.
    private static final float FILTER_RADIUS = 1.00001f;

    private ProbeLightmapMipChain() {
    }

    static Image build(Mesh mesh, ProbeLightmapChartOwnership.Charts charts, ByteBuffer base,
            int[] baseOwners, BitSet baseFilled, int width, int height, boolean skyBand) {
        int bands = skyBand ? 2 : 1;
        if (baseOwners == null || baseFilled.isEmpty()) {
            return new Image(Image.Format.RGBA16F, width, height * bands, base, ColorSpace.Linear);
        }

        List<IndexBuffer> drawIndices = drawIndices(mesh, charts.indices);
        List<ByteBuffer> levels = new ArrayList<>();
        levels.add(base);
        int levelWidth = width;
        int levelHeight = height;
        ByteBuffer source = base;
        int[] sourceOwners = baseOwners;
        BitSet sourceFilled = baseFilled;
        while (levelWidth > 1 && levelHeight > 1 && (levelWidth & 1) == 0 && (levelHeight & 1) == 0) {
            int nextWidth = levelWidth / 2;
            int nextHeight = levelHeight / 2;
            int[] nextOwners = new int[nextWidth * nextHeight];
            Arrays.fill(nextOwners, -1);
            if (!assignFootprints(charts, drawIndices, nextWidth, nextHeight, skyBand, nextOwners)) {
                break;
            }

            ByteBuffer next = downsample(source, levelWidth, levelHeight, bands, sourceOwners, sourceFilled,
                    nextOwners);
            if (next == null) {
                break;
            }
            levels.add(next);
            source = next;
            sourceOwners = nextOwners;
            sourceFilled = null;
            levelWidth = nextWidth;
            levelHeight = nextHeight;
        }
        if (levels.size() == 1) {
            return new Image(Image.Format.RGBA16F, width, height * bands, base, ColorSpace.Linear);
        }

        int[] sizes = new int[levels.size()];
        int total = 0;
        for (int level = 0; level < levels.size(); level++) {
            sizes[level] = levels.get(level).remaining();
            total += sizes[level];
        }
        ByteBuffer packed = BufferUtils.createByteBuffer(total).order(base.order());
        for (ByteBuffer level : levels) {
            packed.put(level.duplicate());
        }
        packed.flip();
        return new Image(Image.Format.RGBA16F, width, height * bands, packed, sizes, ColorSpace.Linear);
    }

    private static List<IndexBuffer> drawIndices(Mesh mesh, IndexBuffer source) {
        List<IndexBuffer> result = new ArrayList<>();
        result.add(source);
        var original = mesh.getBuffer(Type.Index);
        for (int lod = 0; lod < mesh.getNumLodLevels(); lod++) {
            var buffer = mesh.getLodLevel(lod);
            if (buffer == original) {
                continue;
            }
            var indices = buffer == null ? null : buffer.getDataReadOnly();
            if (!(indices instanceof ByteBuffer || indices instanceof ShortBuffer || indices instanceof IntBuffer)) {
                return List.of();
            }
            result.add(IndexBuffer.wrapIndexBuffer(indices));
        }
        return result;
    }

    private static boolean assignFootprints(ProbeLightmapChartOwnership.Charts charts, List<IndexBuffer> draws,
            int width, int height, boolean skyBand, int[] owners) {
        if (draws.isEmpty()) {
            return false;
        }
        for (IndexBuffer indices : draws) {
            if (indices.size() % 3 != 0) {
                return false;
            }
            for (int i = 0; i < indices.size(); i += 3) {
                int a = indices.get(i);
                int b = indices.get(i + 1);
                int c = indices.get(i + 2);
                if (!assignTriangle(charts, width, height, skyBand, owners, a, b, c)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean assignTriangle(ProbeLightmapChartOwnership.Charts charts, int width, int height,
            boolean skyBand, int[] owners, int a, int b, int c) {
        if (a < 0 || a >= charts.owners.length || b < 0 || b >= charts.owners.length
                || c < 0 || c >= charts.owners.length) {
            return false;
        }
        float ax = charts.uv.get(2 * a) * width;
        float ay = charts.uv.get(2 * a + 1) * height;
        float bx = charts.uv.get(2 * b) * width;
        float by = charts.uv.get(2 * b + 1) * height;
        float cx = charts.uv.get(2 * c) * width;
        float cy = charts.uv.get(2 * c + 1) * height;
        int owner = charts.owners[a];
        // A simplified triangle can bridge unrelated charts. Even constant UVs
        // can be visible in 3D and still need their bilinear footprint.
        if (charts.owners[b] != owner || charts.owners[c] != owner) {
            return false;
        }
        float minY = Math.min(ay, Math.min(by, cy));
        float maxY = Math.max(ay, Math.max(by, cy));
        if (skyBand && (minY < .5f || maxY > height - .5f)) {
            return false;
        }
        float minX = Math.min(ax, Math.min(bx, cx));
        float maxX = Math.max(ax, Math.max(bx, cx));
        int firstX = Math.max(0, (int) Math.ceil(minX - FILTER_RADIUS - .5f));
        int lastX = Math.min(width - 1, (int) Math.floor(maxX + FILTER_RADIUS - .5f));
        int firstY = Math.max(0, (int) Math.ceil(minY - FILTER_RADIUS - .5f));
        int lastY = Math.min(height - 1, (int) Math.floor(maxY + FILTER_RADIUS - .5f));
        float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        float orientation = Math.copySign(1f, area);
        for (int y = firstY; y <= lastY; y++) {
            float py = y + .5f;
            for (int x = firstX; x <= lastX; x++) {
                float px = x + .5f;
                if (area != 0f && (outside(ax, ay, bx, by, px, py, orientation)
                        || outside(bx, by, cx, cy, px, py, orientation)
                        || outside(cx, cy, ax, ay, px, py, orientation))) {
                    continue;
                }
                int pixel = y * width + x;
                if (owners[pixel] >= 0 && owners[pixel] != owner) {
                    return false;
                }
                owners[pixel] = owner;
            }
        }
        return true;
    }

    private static boolean outside(float ax, float ay, float bx, float by, float px, float py, float orientation) {
        float dx = bx - ax;
        float dy = by - ay;
        // Separating axis between the triangle edge and the filter's square.
        float reach = FILTER_RADIUS * (Math.abs(dx) + Math.abs(dy));
        return orientation * (dx * (py - ay) - dy * (px - ax)) < -reach;
    }

    private static ByteBuffer downsample(ByteBuffer source, int width, int height, int bands,
            int[] sourceOwners, BitSet sourceFilled, int[] owners) {
        int outWidth = width / 2;
        int sourceBandPixels = width * height;
        ByteBuffer next = ByteBuffer.allocate(owners.length * bands * TEXEL_BYTES).order(source.order());
        int[] taps = new int[4];
        for (int pixel = 0; pixel < owners.length; pixel++) {
            int owner = owners[pixel];
            if (owner < 0) {
                continue;
            }
            int x = 2 * (pixel % outWidth);
            int y = 2 * (pixel / outWidth);
            int count = 0;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    int sample = (y + dy) * width + x + dx;
                    if (isValidSource(sourceOwners, sourceFilled, owner, sample)) {
                        taps[count++] = sample;
                    }
                }
            }
            if (count == 0) {
                int nearest = nearestSource(x, y, width, height, owner, sourceOwners, sourceFilled);
                if (nearest < 0) {
                    return null;
                }
                taps[count++] = nearest;
            }
            for (int band = 0; band < bands; band++) {
                int outOffset = (pixel + band * owners.length) * TEXEL_BYTES;
                int sourceOffset = band * sourceBandPixels;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    float value = 0f;
                    for (int tap = 0; tap < count; tap++) {
                        int offset = (sourceOffset + taps[tap]) * TEXEL_BYTES + channel * Short.BYTES;
                        value += FastMath.convertHalfToFloat(source.getShort(offset));
                    }
                    next.putShort(outOffset + channel * Short.BYTES, FastMath.convertFloatToHalf(value / count));
                }
            }
        }
        return next;
    }

    private static int nearestSource(int x, int y, int width, int height, int owner,
            int[] sourceOwners, BitSet sourceFilled) {
        int nearest = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int sy = Math.max(0, y - 2); sy < Math.min(height, y + 4); sy++) {
            for (int sx = Math.max(0, x - 2); sx < Math.min(width, x + 4); sx++) {
                int dx = sx * 2 - (x * 2 + 1);
                int dy = sy * 2 - (y * 2 + 1);
                int distance = dx * dx + dy * dy;
                int sample = sy * width + sx;
                if (distance < bestDistance && isValidSource(sourceOwners, sourceFilled, owner, sample)) {
                    nearest = sample;
                    bestDistance = distance;
                }
            }
        }
        return nearest;
    }

    private static boolean isValidSource(int[] sourceOwners, BitSet sourceFilled, int owner, int sample) {
        return sourceOwners[sample] == owner && (sourceFilled == null || sourceFilled.get(sample));
    }
}
