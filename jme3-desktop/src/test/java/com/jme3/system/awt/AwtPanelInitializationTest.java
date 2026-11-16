package com.jme3.system.awt;

import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.system.NullRenderer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AwtPanelInitializationTest {
    @Test
    void visiblePanelMustNotDrawBeforeViewportInitialization() {
        AwtPanel panel = new AwtPanel(PaintMode.Accelerated) {
            @Override
            public boolean checkVisibilityState() {
                return true;
            }
        };
        assertDoesNotThrow(panel::onFrameEnd);
        assertDoesNotThrow(panel::drawFrameInThread);
        assertDoesNotThrow(panel::repaintInThread);
        assertFalse(panel.isInitialized());
    }

    @Test
    void earlyResizeDoesNotCreateFalseInitializedFramebuffer() throws Exception {
        AwtPanel panel = new AwtPanel(PaintMode.OnRequest);
        SwingUtilities.invokeAndWait(() -> panel.setSize(320, 180));
        assertDoesNotThrow(panel::onFrameEnd);
        assertDoesNotThrow(panel::onFrameEnd);
        assertFalse(panel.isInitialized());
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = new ViewPort("Embedded preview", new Camera(1, 1));
        panel.attachTo(true, view);
        panel.initialize(manager, view);
        assertTrue(panel.isInitialized());
        panel.onFrameEnd();
        assertEquals(320, view.getCamera().getWidth());
        assertEquals(180, view.getCamera().getHeight());
        assertDoesNotThrow(panel::onFrameEnd);
    }
}
