package com.jme3.environment.gi;

import com.jme3.math.FastMath;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer.Type;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline blending of corresponding geometric edges after UV-island dilation. */
final class ProbeLightmapSeamBlend {
    private static final int TEXEL_BYTES = 8;
    private static final float BAND_RADIUS = 1.5f;

    private ProbeLightmapSeamBlend() {
    }

    private static final class Point {
        final float x;
        final float y;

        Point(float x, float y) {
            this.x = x;
            this.y = y;
        }

        boolean matches(Point other) {
            return x == other.x && y == other.y;
        }
    }

    private static final class Side {
        final Point start;
        final Point end;
        final Point inside;

        Side(Point start, Point end, Point inside) {
            this.start = start;
            this.end = end;
            this.inside = inside;
        }
    }

    private static final class Change {
        final float weight;
        final float r;
        final float g;
        final float b;
        final float a;

        Change(float weight, float[] channels) {
            this.weight = weight;
            this.r = channels[0];
            this.g = channels[1];
            this.b = channels[2];
            this.a = channels.length == 4 ? channels[3] : 0f;
        }
    }

    static void blend(Mesh mesh, ByteBuffer pixels, int width, int height, int[] owners) {
        blend(mesh, pixels, width, height, owners, null);
    }

    static void blendDirections(Mesh mesh, ByteBuffer pixels, int width, int height, int[] owners, BitSet filled) {
        blend(mesh, pixels, width, height, owners, filled);
    }

    private static void blend(Mesh mesh, ByteBuffer pixels, int width, int height, int[] owners, BitSet filled) {
        if (owners == null
                || !(mesh.getBuffer(Type.Position).getData() instanceof FloatBuffer)
                || !(mesh.getBuffer(Type.Normal).getData() instanceof FloatBuffer)
                || mesh.getBuffer(Type.Position).getNumComponents() != 3
                || mesh.getBuffer(Type.Normal).getNumComponents() != 3
                || mesh.getBuffer(Type.Position).getStride() != 0
                || mesh.getBuffer(Type.Normal).getStride() != 0) {
            return;
        }
        FloatBuffer positions = mesh.getFloatBuffer(Type.Position);
        FloatBuffer normals = mesh.getFloatBuffer(Type.Normal);
        FloatBuffer uv = mesh.getFloatBuffer(Type.TexCoord2);
        int[] welded = new int[mesh.getVertexCount()];
        if (positions.limit() < welded.length * 3 || normals.limit() < welded.length * 3) {
            return;
        }
        Map<List<Float>, Integer> vertices = new HashMap<>();
        for (int i = 0; i < welded.length; i++) {
            int p = i * 3;
            List<Float> vertex = java.util.Arrays.asList(positions.get(p) + 0f, positions.get(p + 1) + 0f, positions.get(p + 2) + 0f,
                    normals.get(p) + 0f, normals.get(p + 1) + 0f, normals.get(p + 2) + 0f);
            welded[i] = vertices.computeIfAbsent(vertex, key -> vertices.size());
        }

        Map<Long, List<Side>> edges = new HashMap<>();
        var indices = mesh.getIndicesAsList();
        for (int i = 0; i + 2 < indices.size(); i += 3) {
            int a = indices.get(i);
            int b = indices.get(i + 1);
            int c = indices.get(i + 2);
            add(edges, welded, uv, width, height, a, b, c);
            add(edges, welded, uv, width, height, b, c, a);
            add(edges, welded, uv, width, height, c, a, b);
        }

        // Read only the original atlas. One strongest correction per texel
        // prevents a seam junction from repeatedly mixing the same texel.
        Map<Integer, Change> changes = new HashMap<>();
        for (List<Side> pair : edges.values()) {
            if (pair.size() != 2) {
                continue;
            }
            Side a = pair.get(0);
            Side b = pair.get(1);
            if (!a.start.matches(b.start) || !a.end.matches(b.end)) {
                blendSide(a, b, pixels, width, height, owners, filled, changes);
                blendSide(b, a, pixels, width, height, owners, filled, changes);
            }
        }
        for (var entry : changes.entrySet()) {
            int offset = entry.getKey() * TEXEL_BYTES;
            Change change = entry.getValue();
            float coverage = coverage(pixels, entry.getKey(), filled);
            write(pixels, offset, change.r, change.weight, coverage);
            write(pixels, offset + 2, change.g, change.weight, coverage);
            write(pixels, offset + 4, change.b, change.weight, coverage);
            if (filled != null) {
                // The signed reference in alpha must follow the same blend as its gradient.
                write(pixels, offset + 6, change.a, change.weight, coverage);
            }
        }
    }

    private static void add(Map<Long, List<Side>> edges, int[] welded, FloatBuffer uv, int width, int height,
            int a, int b, int c) {
        if (welded[a] == welded[b]) {
            return;
        }
        if (welded[a] > welded[b]) {
            int swap = a;
            a = b;
            b = swap;
        }
        long key = ((long) welded[a] << 32) | (welded[b] & 0xffffffffL);
        edges.computeIfAbsent(key, unused -> new ArrayList<>(2)).add(
                new Side(point(uv, a, width, height), point(uv, b, width, height), point(uv, c, width, height)));
    }

    private static Point point(FloatBuffer uv, int vertex, int width, int height) {
        return new Point(uv.get(vertex * 2) * width, uv.get(vertex * 2 + 1) * height);
    }

    private static void blendSide(Side destination, Side source, ByteBuffer pixels, int width, int height,
            int[] owners, BitSet filled, Map<Integer, Change> changes) {
        float dx = destination.end.x - destination.start.x;
        float dy = destination.end.y - destination.start.y;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-6f) {
            return;
        }
        int firstX = Math.max(0, (int) Math.floor(Math.min(destination.start.x, destination.end.x) - BAND_RADIUS));
        int lastX = Math.min(width - 1, (int) Math.floor(Math.max(destination.start.x, destination.end.x) + BAND_RADIUS));
        int firstY = Math.max(0, (int) Math.floor(Math.min(destination.start.y, destination.end.y) - BAND_RADIUS));
        int lastY = Math.min(height - 1, (int) Math.floor(Math.max(destination.start.y, destination.end.y) + BAND_RADIUS));
        float[] channels = new float[filled == null ? 3 : 4];
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                float px = x + .5f - destination.start.x;
                float py = y + .5f - destination.start.y;
                float t = (px * dx + py * dy) / lengthSquared;
                if (t < 0f || t > 1f) {
                    continue;
                }
                float distanceX = px - t * dx;
                float distanceY = py - t * dy;
                float distance = (float) Math.sqrt(distanceX * distanceX + distanceY * distanceY);
                if (distance >= BAND_RADIUS) {
                    continue;
                }

                // Query both UVs at the same point along the 3D edge, shifted
                // slightly into each triangle to identify its own island.
                Point dst = interior(destination, t);
                Point src = interior(source, t);
                int pixel = y * width + x;
                if (owners[pixel] < 0 || owners[pixel] != ownerAt(dst, width, height, owners)
                        || coverage(pixels, pixel, filled) <= 0f) {
                    continue;
                }
                float weight = .5f * (1f - distance / BAND_RADIUS);
                Change existing = changes.get(pixel);
                if ((existing == null || weight > existing.weight)
                        && sample(pixels, src, width, height, owners, filled, channels)) {
                    changes.put(pixel, new Change(weight, channels));
                }
            }
        }
    }

    private static Point interior(Side side, float t) {
        float x = side.start.x + (side.end.x - side.start.x) * t;
        float y = side.start.y + (side.end.y - side.start.y) * t;
        float dx = side.inside.x - x;
        float dy = side.inside.y - y;
        float scale = Math.min(.25f, .25f / Math.max(1e-6f, (float) Math.sqrt(dx * dx + dy * dy)));
        return new Point(x + scale * dx, y + scale * dy);
    }

    private static int ownerAt(Point point, int width, int height, int[] owners) {
        int x = Math.max(0, Math.min(width - 1, (int) point.x));
        int y = Math.max(0, Math.min(height - 1, (int) point.y));
        return owners[y * width + x];
    }

    private static boolean sample(ByteBuffer pixels, Point point, int width, int height, int[] owners,
            BitSet filled, float[] channels) {
        int chart = ownerAt(point, width, height, owners);
        if (chart < 0) {
            return false;
        }
        float x = point.x - .5f;
        float y = point.y - .5f;
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float sum = 0f;
        java.util.Arrays.fill(channels, 0f);
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int column = x0 + dx;
                int row = y0 + dy;
                if (column < 0 || column >= width || row < 0 || row >= height) {
                    continue;
                }
                int pixel = row * width + column;
                if (owners[pixel] != chart) {
                    continue;
                }
                float coverage = coverage(pixels, pixel, filled);
                if (coverage <= 0f) {
                    continue;
                }
                float weight = (dx == 0 ? 1f - fx : fx) * (dy == 0 ? 1f - fy : fy);
                // Compare per-covered irradiance for the base RGB bake. For the
                // companion texture the validity bit only excludes empty texels.
                float scale = weight / coverage;
                for (int channel = 0; channel < channels.length; channel++) {
                    channels[channel] += FastMath.convertHalfToFloat(pixels.getShort(pixel * TEXEL_BYTES + channel * 2)) * scale;
                }
                sum += weight;
            }
        }
        if (sum <= 0f) {
            return false;
        }
        for (int channel = 0; channel < channels.length; channel++) {
            channels[channel] /= sum;
        }
        return true;
    }

    private static float coverage(ByteBuffer pixels, int pixel, BitSet filled) {
        if (filled != null) {
            return filled.get(pixel) ? 1f : 0f;
        }
        return FastMath.convertHalfToFloat(pixels.getShort(pixel * TEXEL_BYTES + 6));
    }

    private static void write(ByteBuffer pixels, int offset, float value, float weight, float coverage) {
        float original = FastMath.convertHalfToFloat(pixels.getShort(offset));
        pixels.putShort(offset, FastMath.convertFloatToHalf(original + weight * (coverage * value - original)));
    }
}
