package com.jme3.renderer.gpuscene;

/**
 * Immutable completed Hi-Z sample, obtained from GpuScenePipeline.getHiZStatistics.
 * Counts are geometry instances, not model roots. Before/after counts and GPU
 * timings belong to the same sampled frame. See setHiZDiagnosticsEnabled for usage.
 */
public final class HiZStatistics {
    private final long candidates;
    private final long survivors;
    private final double depthMs;
    private final double historyMs;
    private final double pyramidMs;
    private final double cullingMs;
    private final long capturedAt;
    private final long depthTriangles;
    private final long survivingTriangles;

    HiZStatistics(long candidates, long survivors, double depthMs, double pyramidMs,
            double cullingMs, double historyMs, long capturedAt, long depthTriangles, long survivingTriangles) {
        this.candidates = candidates;
        this.survivors = survivors;
        this.depthMs = depthMs;
        this.historyMs = historyMs;
        this.pyramidMs = pyramidMs;
        this.cullingMs = cullingMs;
        this.capturedAt = capturedAt;
        this.depthTriangles = depthTriangles;
        this.survivingTriangles = survivingTriangles;
    }

    /** @return instances surviving the preceding frustum/distance/screen-size tests */
    public long getCandidates() {
        return candidates;
    }

    /** @return instances remaining after Hi-Z, summed across all LODs */
    public long getSurvivors() {
        return survivors;
    }

    /** @return candidates rejected specifically by the Hi-Z stage */
    public long getOccluded() {
        return candidates - survivors;
    }

    /** @return actual prepass command triangles, after history selection and occluder policy */
    public long getDepthTriangles() {
        return depthTriangles;
    }

    /** @return triangles in the final surviving commands, before shading-pass multiplication */
    public long getSurvivingTriangles() {
        return survivingTriangles;
    }

    /** @return history occluder selection GPU milliseconds (empty stage in SAME_FRAME) */
    public double getHistoryMilliseconds() {
        return historyMs;
    }

    /** @return depth prepass GPU milliseconds */
    public double getDepthMilliseconds() {
        return depthMs;
    }

    /** @return depth-pyramid reduction GPU milliseconds */
    public double getPyramidMilliseconds() {
        return pyramidMs;
    }

    /** @return second cull/LOD/compaction dispatch GPU milliseconds, including Hi-Z tests */
    public double getCullingMilliseconds() {
        return cullingMs;
    }

    /** @return sum of measured Hi-Z stages; excludes scene shading and diagnostic copies */
    public double getTotalMilliseconds() {
        return depthMs + pyramidMs + cullingMs + historyMs;
    }

    /** @return sample age in milliseconds, based on the monotonic clock */
    public double getAgeMilliseconds() {
        return (System.nanoTime() - capturedAt) / 1000000.0;
    }
}
