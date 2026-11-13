package com.jme3.shader;

import com.jme3.shader.bufferobject.BufferObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShaderBufferBlockBindingTest {
    @Test
    void sharingBufferAcrossProgramsInvalidatesEachProgramsBindingIndependently() {
        BufferObject buffer = new BufferObject();
        buffer.setBinding(2);
        ShaderBufferBlock cull = new ShaderBufferBlock();
        ShaderBufferBlock compact = new ShaderBufferBlock();
        cull.setBufferObject(ShaderBufferBlock.BufferType.ShaderStorageBufferObject, buffer);
        compact.setBufferObject(ShaderBufferBlock.BufferType.ShaderStorageBufferObject, buffer);
        cull.clearUpdateNeeded();
        compact.clearUpdateNeeded();

        buffer.setBinding(0);
        assertTrue(cull.isUpdateNeeded());
        assertTrue(compact.isUpdateNeeded());
        compact.clearUpdateNeeded();
        assertFalse(compact.isUpdateNeeded());
        assertTrue(cull.isUpdateNeeded());

        buffer.setBinding(2);
        assertFalse(cull.isUpdateNeeded());
        assertTrue(compact.isUpdateNeeded());
        cull.reset();
        assertTrue(cull.isUpdateNeeded());
    }
}
