package com.jme3.renderer.opengl;

import com.jme3.renderer.BufferReadback;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Context-owned GPU copy plus fence; no polling call waits. */
final class GLBufferReadback implements BufferReadback {
    private final GL gl;
    private final GLExt ext;
    private final int size;
    private final IntBuffer scratch = BufferUtils.createIntBuffer(16);
    private int buffer;
    private Object fence;
    private boolean closed;
    private final java.util.Set<GLBufferReadback> owner;

    GLBufferReadback(GL gl, GL3 gl3, GLExt ext, int source, long offset, int size,
            java.util.Set<GLBufferReadback> owner) {
        this.gl = gl;
        this.ext = ext;
        this.size = size;
        this.owner = owner;
        int previousRead = binding(0x8F36);
        int previousWrite = binding(0x8F37);
        try {
            gl.glBindBuffer(0x8F36, source);
            long capacity = gl3.glGetBufferParameter(0x8F36, 0x8764);
            if (offset > capacity || size > capacity - offset) {
                throw new IllegalArgumentException("Snapshot exceeds source storage");
            }
            scratch.clear();
            scratch.limit(1);
            gl.glGenBuffers(scratch);
            buffer = scratch.get(0);
            gl.glBindBuffer(0x8F37, buffer);
            gl.glBufferData(0x8F37, size, 0x88E1);
            gl3.glCopyBufferSubData(0x8F36, 0x8F37, offset, 0, size);
            fence = ext.glFenceSync(0x9117, 0);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        } finally {
            gl.glBindBuffer(0x8F36, previousRead);
            gl.glBindBuffer(0x8F37, previousWrite);
        }
    }

    private int binding(int target) {
        scratch.clear();
        gl.glGetInteger(target, scratch);
        return scratch.get(0);
    }

    @Override
    public boolean isReady() {
        if (closed) {
            throw new IllegalStateException("Snapshot is closed or context was lost");
        }
        int status = ext.glClientWaitSync(fence, 0, 0);
        return status == 0x911A || status == 0x911C;
    }

    @Override
    public void read(ByteBuffer destination) {
        if (!isReady()) {
            throw new IllegalStateException("Snapshot is still pending");
        }
        if (destination == null || !destination.isDirect() || destination.isReadOnly()
                || destination.remaining() < size) {
            throw new IllegalArgumentException("Direct writable destination is too small");
        }
        int previous = binding(0x8F36);
        try {
            gl.glBindBuffer(0x8F36, buffer);
            ByteBuffer target = destination.duplicate();
            target.limit(target.position() + size);
            gl.glGetBufferSubData(0x8F36, 0, target);
        } finally {
            gl.glBindBuffer(0x8F36, previous);
        }
    }

    void invalidate() {
        closed = true;
        buffer = 0;
        fence = null;
        owner.remove(this);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (fence != null) {
            ext.glDeleteSync(fence);
        }
        if (buffer != 0) {
            scratch.clear();
            scratch.put(buffer).flip();
            gl.glDeleteBuffers(scratch);
        }
        invalidate();
    }
}
