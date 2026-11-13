package com.jme3.shader;

import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class UniformArrayUpdateTest {
    @Test
    public void sharedShaderUpdatesAndShrinksIntegerArrays() {
        Uniform uniform = new Uniform();
        uniform.setValue(VarType.IntArray, new int[]{100, 50, 10});
        uniform.setValue(VarType.IntArray, new int[]{60, 20});
        IntBuffer value = (IntBuffer) uniform.getValue();
        assertEquals(2, value.remaining());
        assertEquals(60, value.get(0));
        assertEquals(20, value.get(1));
    }

    @Test
    public void floatArraysDoNotUploadStaleTrailingValues() {
        Uniform uniform = new Uniform();
        uniform.setValue(VarType.FloatArray, new float[]{80, 30, 10, 3});
        uniform.setValue(VarType.FloatArray, new float[]{15});
        assertEquals(1, uniform.getMultiData().remaining());
        assertEquals(15, uniform.getMultiData().get(0), 0);
    }
}
