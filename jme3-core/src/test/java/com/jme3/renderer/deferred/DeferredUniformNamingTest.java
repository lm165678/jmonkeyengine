package com.jme3.renderer.deferred;

import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeferredUniformNamingTest {
    @Test
    public void nativeUniformNamesMatchComputeAndLightingAliases() {
        assertTrue("Run this regression with assertions enabled", Shader.class.desiredAssertionStatus());
        Shader shader = new Shader();
        String defines = DeferredRenderContext.uniformDefines();
        for (String line : defines.split("\n")) {
            String[] tokens = line.split(" ");
            DeferredRenderContext.uniform(shader, tokens[1], VarType.Int, 6);
            assertEquals("m_" + tokens[1], tokens[2]);
            assertEquals(6, shader.getUniform(tokens[2]).getValue());
        }
    }
}
