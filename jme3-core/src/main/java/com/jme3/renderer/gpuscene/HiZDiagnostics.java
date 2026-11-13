package com.jme3.renderer.gpuscene;

import com.jme3.renderer.BufferReadback;
import com.jme3.renderer.Renderer;
import com.jme3.shader.BufferObject;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Bounded, throttled asynchronous diagnostics. One sample in flight, no blocking wait. */
final class HiZDiagnostics implements AutoCloseable {
    private final Renderer renderer;
    private final int[] queries;
    private final List<BufferReadback> before = new ArrayList<>();
    private final List<BufferReadback> depth = new ArrayList<>();
    private final List<BufferReadback> after = new ArrayList<>();
    private final List<Integer> levels = new ArrayList<>();
    private final List<Boolean> occluders = new ArrayList<>();
    private ByteBuffer bytes = BufferUtils.createByteBuffer(8 * 20);
    private boolean capturing;
    private boolean pending;
    private boolean queryActive;
    private long capturedAt;
    private final long warmupUntil = System.nanoTime() + 250000000L;
    private HiZStatistics latest;

    HiZDiagnostics(Renderer renderer) {
        this.renderer = renderer;
        queries = renderer.generateProfilingTasks(4);
    }

    HiZStatistics latest() {
        return latest;
    }

    void beginSample(boolean force) {
        poll();
        if (!pending && (force || System.nanoTime() >= warmupUntil) && !renderer.isProfilingActive()
                && (force || System.nanoTime() - capturedAt >= 250000000L)) {
            capturing = true;
            capturedAt = System.nanoTime();
        }
    }

    void snapshot(BufferObject commands, int count, boolean afterCulling, boolean occluder) {
        if (!capturing) {
            return;
        }
        if (bytes.capacity() < count * 20) {
            bytes = BufferUtils.createByteBuffer(count * 20);
        }
        BufferReadback request = renderer.requestBufferReadback(commands, 0, count * 20);
        if (afterCulling) {
            after.add(request);
        } else {
            before.add(request);
            levels.add(count);
            occluders.add(occluder);
        }
    }

    void snapshotDepth(BufferObject commands, int count) {
        if (capturing) {
            depth.add(renderer.requestBufferReadback(commands, 0, count * 20));
        }
    }

    void start(int stage) {
        if (capturing) {
            renderer.startProfiling(queries[stage]);
            queryActive = true;
        }
    }

    void stop() {
        if (queryActive) {
            renderer.stopProfiling();
            queryActive = false;
        }
    }

    void finishSample() {
        stop();
        if (capturing) {
            pending = true;
            capturing = false;
        }
    }

    void poll() {
        if (!pending) {
            return;
        }
        for (int query : queries) {
            if (!renderer.isTaskResultAvailable(query)) {
                return;
            }
        }
        for (BufferReadback request : before) {
            if (!request.isReady()) {
                return;
            }
        }
        for (BufferReadback request : after) {
            if (!request.isReady()) {
                return;
            }
        }
        for (BufferReadback request : depth) {
            if (!request.isReady()) {
                return;
            }
        }
        latest = new HiZStatistics(count(before, false, false), count(after, false, false), renderer.getProfilingTime(queries[0]) / 1000000.0,
                renderer.getProfilingTime(queries[1]) / 1000000.0,
                renderer.getProfilingTime(queries[2]) / 1000000.0,
                renderer.getProfilingTime(queries[3]) / 1000000.0, capturedAt,
                count(depth, true, true), count(after, true, false));
        releaseSnapshots();
        pending = false;
    }

    private long count(List<BufferReadback> requests, boolean triangles, boolean depthOnly) {
        long total = 0;
        for (int i = 0; i < requests.size(); i++) {
            if (depthOnly && !occluders.get(i)) {
                continue;
            }
            bytes.clear();
            requests.get(i).read(bytes);
            for (int level = 0; level < levels.get(i); level++) {
                long instances = Integer.toUnsignedLong(bytes.getInt(level * 20 + 4));
                total += instances * (triangles ? Integer.toUnsignedLong(bytes.getInt(level * 20)) / 3 : 1);
            }
        }
        return total;
    }

    private void releaseSnapshots() {
        for (BufferReadback request : before) {
            request.close();
        }
        for (BufferReadback request : after) {
            request.close();
        }
        for (BufferReadback request : depth) {
            request.close();
        }
        depth.clear();
        before.clear();
        after.clear();
        levels.clear();
        occluders.clear();
    }

    @Override
    public void close() {
        stop();
        releaseSnapshots();
        renderer.deleteProfilingTasks(queries);
    }
}
