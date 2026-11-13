package com.jme3.renderer.opengl;

import com.jme3.renderer.Statistics;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Nonblocking command snapshots for Statistics. Three bounded readback slots are
 * reused only after their fences signal. Busy/overflowed frames are skipped;
 * the HUD retains the last complete sample and reports its age. No glFinish,
 * nonzero client wait, or read of an unfinished buffer is allowed here.
 */
final class IndirectDrawStatistics {
    private static final int READ = 0x8F36;
    private static final int WRITE = 0x8F37;
    private static final int CAPACITY = 4096;
    private final GL gl;
    private final GL3 gl3;
    private final GLExt ext;
    private final Statistics statistics;
    private final Slot[] slots = {new Slot(), new Slot(), new Slot()};
    private final IntBuffer integer = BufferUtils.createIntBuffer(16);
    private final ByteBuffer result = BufferUtils.createByteBuffer(CAPACITY * 20);
    private Slot current;
    private boolean attempted;

    private static final class Slot {
        int buffer;
        Object fence;
        int count;
        final int[] vertices = new int[CAPACITY];
        boolean overflow;
        long frame;
        long cpuTriangles;
        long cpuVertices;
    }

    IndirectDrawStatistics(GL gl, GL3 gl3, GLExt ext, Statistics statistics) {
        this.gl = gl;
        this.gl3 = gl3;
        this.ext = ext;
        this.statistics = statistics;
    }

    private int binding(int target) {
        integer.clear();
        gl.glGetInteger(target, integer);
        return integer.get(0);
    }

    void recordRange(int commands, long offset, int drawCount, int vertices, int[] sourceVertices) {
        if (!attempted) {
            poll();
            attempted = true;
            for (Slot slot : slots) {
                if (slot.fence == null) {
                    current = slot;
                    slot.count = 0;
                    slot.overflow = false;
                    break;
                }
            }
        }
        if (current == null) {
            return;
        }
        if (drawCount > CAPACITY - current.count) {
            current.overflow = true;
            return;
        }
        int previousRead = binding(READ);
        int previousWrite = binding(WRITE);
        try {
            if (current.buffer == 0) {
                integer.clear();
                integer.limit(1);
                gl.glGenBuffers(integer);
                current.buffer = integer.get(0);
                gl.glBindBuffer(WRITE, current.buffer);
                gl.glBufferData(WRITE, CAPACITY * 20L, 0x88E1); // STREAM_READ
            } else {
                gl.glBindBuffer(WRITE, current.buffer);
            }
            gl.glBindBuffer(READ, commands);
            gl3.glCopyBufferSubData(READ, WRITE, offset, current.count * 20L, drawCount * 20L);
            for (int draw = 0; draw < drawCount; draw++) {
                current.vertices[current.count++] = sourceVertices == null ? vertices : sourceVertices[draw];
            }
        } finally {
            gl.glBindBuffer(READ, previousRead);
            gl.glBindBuffer(WRITE, previousWrite);
        }
    }

    void endFrame() {
        if (current != null) {
            current.frame = statistics.getFrameSequence();
            current.cpuTriangles = statistics.getDirectTriangleCount();
            current.cpuVertices = statistics.getDirectVertexCount();
            current.fence = ext.glFenceSync(0x9117, 0); // SYNC_GPU_COMMANDS_COMPLETE
            current = null;
        }
        attempted = false;
        poll();
    }

    private void poll() {
        for (Slot slot : slots) {
            if (slot.fence == null) {
                continue;
            }
            int status = ext.glClientWaitSync(slot.fence, 0, 0);
            if (status != 0x911A && status != 0x911C) { // ALREADY_SIGNALED / CONDITION_SATISFIED
                continue;
            }
            if (!slot.overflow) {
                int previous = binding(READ);
                try {
                    gl.glBindBuffer(READ, slot.buffer);
                    result.clear();
                    result.limit(slot.count * 20);
                    gl.glGetBufferSubData(READ, 0, result);
                    long triangles = slot.cpuTriangles;
                    long vertices = slot.cpuVertices;
                    for (int i = 0; i < slot.count; i++) {
                        long count = Integer.toUnsignedLong(result.getInt(i * 20));
                        long instances = Integer.toUnsignedLong(result.getInt(i * 20 + 4));
                        triangles += count / 3 * instances;
                        vertices += slot.vertices[i] * instances;
                    }
                    statistics.publishGeometrySample(slot.frame, triangles, vertices);
                } finally {
                    gl.glBindBuffer(READ, previous);
                }
            }
            ext.glDeleteSync(slot.fence);
            slot.fence = null;
        }
    }

    void close(boolean contextLost) {
        for (Slot slot : slots) {
            if (!contextLost) {
                if (slot.fence != null) {
                    ext.glDeleteSync(slot.fence);
                }
                if (slot.buffer != 0) {
                    integer.clear();
                    integer.put(slot.buffer).flip();
                    gl.glDeleteBuffers(integer);
                }
            }
            slot.fence = null;
            slot.buffer = 0;
        }
        statistics.invalidateGeometrySample();
    }
}
