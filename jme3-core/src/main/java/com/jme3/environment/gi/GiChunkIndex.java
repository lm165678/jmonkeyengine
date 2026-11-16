package com.jme3.environment.gi;

import java.util.*;

/** Immutable double-precision directory BVH; no textures or scene objects retained. */
final class GiChunkIndex {
    private final double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    private final double[] high = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    private GiChunkIndex left;
    private GiChunkIndex right;
    private List<GiProbeStreamer.Chunk> leaves;

    GiChunkIndex(Collection<GiProbeStreamer.Chunk> chunks) {
        List<GiProbeStreamer.Chunk> sorted = new ArrayList<>(chunks);
        for (GiProbeStreamer.Chunk chunk : sorted) {
            double[] origin = {chunk.x, chunk.y, chunk.z};
            double[] size = {chunk.size.x, chunk.size.y, chunk.size.z};
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = Math.min(low[axis], origin[axis]);
                high[axis] = Math.max(high[axis], origin[axis] + size[axis]);
            }
        }
        if (sorted.size() <= 8) {
            leaves = sorted;
            return;
        }
        int longest = 0;
        for (int axis = 1; axis < 3; axis++) {
            if (high[axis] - low[axis] > high[longest] - low[longest]) {
                longest = axis;
            }
        }
        final int axis = longest;
        sorted.sort(Comparator.comparingDouble(chunk -> axis == 0 ? chunk.x : axis == 1 ? chunk.y : chunk.z));
        int middle = sorted.size() / 2;
        left = new GiChunkIndex(sorted.subList(0, middle));
        right = new GiChunkIndex(sorted.subList(middle, sorted.size()));
    }

    void query(double x, double y, double z, double radius, List<GiProbeStreamer.Chunk> result) {
        if (x + radius < low[0] || x - radius > high[0]
                || y + radius < low[1] || y - radius > high[1]
                || z + radius < low[2] || z - radius > high[2]) {
            return;
        }
        if (leaves != null) {
            result.addAll(leaves);
        } else {
            left.query(x, y, z, radius, result);
            right.query(x, y, z, radius, result);
        }
    }
}
