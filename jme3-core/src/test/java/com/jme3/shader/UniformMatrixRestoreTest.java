package com.jme3.shader;

import com.jme3.math.Matrix3f;
import com.jme3.math.Matrix4f;
import org.junit.Test;
import static org.junit.Assert.*;

public class UniformMatrixRestoreTest {
    @Test
    public void sameMatrixRestoresAfterAnotherMaterialClearsIt() {
        Uniform uniform = new Uniform();
        uniform.setValue(VarType.Matrix4, new Matrix4f());
        uniform.clearValue();
        assertEquals(0, uniform.getMultiData().get(0), 0);
        uniform.setValue(VarType.Matrix4, new Matrix4f());
        assertEquals(1, uniform.getMultiData().get(0), 0);
        assertEquals(1, uniform.getMultiData().get(15), 0);
    }

    @Test
    public void normalMatrixAlsoRestores() {
        Uniform uniform = new Uniform();
        uniform.setValue(VarType.Matrix3, new Matrix3f());
        uniform.clearValue();
        uniform.setValue(VarType.Matrix3, new Matrix3f());
        assertEquals(1, uniform.getMultiData().get(0), 0);
        assertEquals(1, uniform.getMultiData().get(8), 0);
    }
}
