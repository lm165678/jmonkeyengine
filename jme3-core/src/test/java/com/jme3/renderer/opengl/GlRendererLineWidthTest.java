package com.jme3.renderer.opengl;

import com.jme3.material.RenderState;
import com.jme3.renderer.Caps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlRendererLineWidthTest {
    @Test
    void coreProfileClampsWideLinesAndCachesEffectiveState() {
        GL gl = mock(GL.class);
        GLRenderer renderer = new GLRenderer(gl, mock(GLExt.class), mock(GLFbo.class));
        renderer.getCaps().add(Caps.CoreProfile);
        RenderState state = new RenderState();
        state.setLineWidth(4f);
        renderer.applyRenderState(state);
        verify(gl, never()).glLineWidth(4f);
        assertEquals(4f, state.getLineWidth());
        clearInvocations(gl);
        state.setLineWidth(2f);
        renderer.applyRenderState(state);
        verifyNoInteractions(gl);
    }

    @Test
    void rejectsNonFiniteWidths() {
        RenderState state = new RenderState();
        assertThrows(IllegalArgumentException.class, () -> state.setLineWidth(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> state.setLineWidth(Float.POSITIVE_INFINITY));
    }
}
