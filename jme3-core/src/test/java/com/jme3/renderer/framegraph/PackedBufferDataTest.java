package com.jme3.renderer.framegraph;

import com.jme3.shader.bufferobject.BufferObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackedBufferDataTest {
    @Test
    void indirectCommandsRemainTightlyPackedAndReuseStorage() {
        BufferObject buffer = PackedBufferData.create(3);
        PackedBufferData.set(buffer, new int[] {36, 2, 0, 4, 8});
        ByteBuffer storage = buffer.getData();
        assertEquals(20, storage.remaining());
        assertEquals(ByteOrder.nativeOrder(), storage.order());
        assertEquals(2, storage.getInt(4));
        assertEquals(8, storage.getInt(16));
        buffer.clearUpdateNeeded();
        PackedBufferData.set(buffer, new int[] {18, 1, 5, 6, 7});
        assertSame(storage, buffer.getData());
        assertTrue(buffer.isUpdateNeeded());
        assertEquals(5, storage.getInt(8));
        assertEquals(3, buffer.getBinding());
    }

    @Test
    void floatRecordsRetainTheirByteLayoutAcrossResize() {
        BufferObject buffer = PackedBufferData.create(0);
        PackedBufferData.set(buffer, new float[] {1f, -2f});
        assertEquals(-2f, buffer.getData().getFloat(4));
        PackedBufferData.set(buffer, new float[] {3f, 4f, 5f, 6f});
        assertEquals(16, buffer.getData().remaining());
        assertEquals(6f, buffer.getData().getFloat(12));
    }
}
