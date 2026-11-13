package com.jme3.renderer.opengl;

import com.jme3.renderer.Caps;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class BufferReadbackTest {
    private final GL4 gl = mock(GL4.class);
    private final GLRenderer renderer = new GLRenderer(gl, mock(GLExt.class), mock(GLFbo.class));
    private final BufferObject buffer = com.jme3.renderer.framegraph.PackedBufferData.create(0);

    private void initialize() {
        renderer.getCaps().add(Caps.OpenGL43);
        renderer.getCaps().add(Caps.OpenGL31);
        renderer.getCaps().add(Caps.ShaderStorageBufferObject);
        buffer.setId(7);
        doAnswer(invocation -> {
            ((IntBuffer) invocation.getArguments()[1]).put(0, 19);
            return null;
        }).when(gl).glGetInteger(eq(0x8F36), any(IntBuffer.class));
        when(gl.glGetBufferParameter(0x8F36, 0x8764)).thenReturn(16);
    }

    @Test
    public void readbackPreservesBindingAndDestinationWindow() {
        initialize();
        ByteBuffer destination = BufferUtils.createByteBuffer(12);
        destination.position(4);
        destination.limit(8);
        doAnswer(invocation -> {
            ByteBuffer target = (ByteBuffer) invocation.getArguments()[2];
            target.put((byte) 42);
            return null;
        }).when(gl).glGetBufferSubData(eq(0x8F36), eq(0L), any(ByteBuffer.class));
        renderer.readBufferData(buffer, 0, destination);
        assertEquals(42, destination.get(4));
        assertEquals(4, destination.position());
        assertEquals(8, destination.limit());
        verify(gl).glMemoryBarrier(0x200);
        verify(gl).glBindBuffer(0x8F36, 19);
    }

    @Test
    public void invalidRangeRestoresBindingWithoutIssuingDownload() {
        initialize();
        try {
            renderer.readBufferData(buffer, 15, BufferUtils.createByteBuffer(4));
            fail("Read outside storage accepted");
        } catch (IllegalArgumentException expected) {
            verify(gl).glBindBuffer(0x8F36, 19);
            verify(gl, never()).glGetBufferSubData(anyInt(), anyLong(), any(ByteBuffer.class));
        }
    }

    @Test
    public void driverFailureStillRestoresBinding() {
        initialize();
        doThrow(new IllegalStateException("driver failure")).when(gl)
                .glGetBufferSubData(anyInt(), anyLong(), any(ByteBuffer.class));
        try {
            renderer.readBufferData(buffer, 0, BufferUtils.createByteBuffer(4));
            fail("Expected driver exception");
        } catch (IllegalStateException expected) {
            verify(gl).glBindBuffer(0x8F36, 19);
        }
    }

    @Test
    public void allocatedBufferCanBeAssignedToMaterialRepeatedly() {
        initialize();
        com.jme3.shader.ShaderBufferBlock block = new com.jme3.shader.ShaderBufferBlock();
        block.setBufferObject(com.jme3.shader.ShaderBufferBlock.BufferType.ShaderStorageBufferObject, buffer);
        block.setBufferObject(com.jme3.shader.ShaderBufferBlock.BufferType.ShaderStorageBufferObject, buffer);
        assertSame(buffer, block.getBufferObject());
        assertEquals(7, buffer.getId());
    }
}
