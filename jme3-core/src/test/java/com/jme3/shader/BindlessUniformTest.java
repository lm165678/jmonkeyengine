package com.jme3.shader;

import org.junit.Test;
import static org.junit.Assert.*;

public class BindlessUniformTest {
    @Test
    public void switchingBackToUnitZeroForcesAnUpload() {
        Uniform uniform = new Uniform();
        uniform.setValue(VarType.Int, 0);
        uniform.setTextureHandle(0x123456789ABCDEFL);
        uniform.clearUpdateNeeded();
        uniform.setValue(VarType.Int, 0);
        assertFalse(uniform.hasTextureHandle());
        assertTrue(uniform.isUpdateNeeded());
        assertEquals(0, uniform.getValue());
    }

    @Test
    public void clearingMaterialDoesNotLeaveAResidentHandle() {
        Uniform uniform = new Uniform();
        uniform.setTextureHandle(42);
        uniform.clearValue();
        assertFalse(uniform.hasTextureHandle());
        assertEquals(0, uniform.getValue());
    }
}
