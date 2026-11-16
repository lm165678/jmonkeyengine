package com.jme3.system.awt;

import com.jme3.input.RawInputListener;
import com.jme3.input.awt.AwtMouseInput;
import com.jme3.input.event.*;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.system.NullRenderer;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AwtPanelHiDpiTest {
    private static class ScaledPanel extends AwtPanel {
        double scale = 1;
        ScaledPanel() {
            super(PaintMode.OnRequest);
        }
        @Override
        public GraphicsConfiguration getGraphicsConfiguration() {
            return new GraphicsConfiguration() {
                public GraphicsDevice getDevice() { return null; }
                public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
                public ColorModel getColorModel(int transparency) { return getColorModel(); }
                public AffineTransform getDefaultTransform() { return AffineTransform.getScaleInstance(scale, scale); }
                public AffineTransform getNormalizingTransform() { return new AffineTransform(); }
                public Rectangle getBounds() { return new Rectangle(0, 0, 3840, 2160); }
            };
        }
    }

    @Test
    void framebufferAndPaintingFollowPhysicalPixelsAcrossMonitorChanges() throws Exception {
        ScaledPanel panel = new ScaledPanel();
        SwingUtilities.invokeAndWait(() -> panel.setSize(800, 600));
        Camera camera = new Camera(1, 1);
        ViewPort viewport = new ViewPort("DPI", camera);
        panel.attachTo(false, viewport);
        panel.initialize(new RenderManager(new NullRenderer()), viewport);
        for (double scale : new double[]{1, 1.25, 1.5, 1}) {
            panel.scale = scale;
            panel.onFrameEnd();
            assertEquals((int) (800 * scale), camera.getWidth());
            assertEquals((int) (600 * scale), camera.getHeight());
            assertEquals(scale, panel.getFramebufferScaleX());
            assertEquals(scale, panel.getFramebufferScaleY());
            Field imageField = AwtPanel.class.getDeclaredField("img");
            imageField.setAccessible(true);
            BufferedImage source = (BufferedImage) imageField.get(panel);
            for (int x = 0; x < source.getWidth(); x++) {
                for (int y = 0; y < source.getHeight(); y++) {
                    source.setRGB(x, y, x % 2 == 0 ? 0xffffff : 0);
                }
            }
            BufferedImage output = new BufferedImage(camera.getWidth(), camera.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            graphics.scale(scale, scale);
            panel.paint(graphics);
            graphics.dispose();
            for (int x = 0; x < output.getWidth(); x++) {
                assertEquals(x % 2 == 0 ? 0xffffff : 0, output.getRGB(x, 100) & 0xffffff,
                        "Physical single-pixel stripes must not be stretched/blurred at " + scale);
            }
        }
    }

    @Test
    void pickingAndMouseMotionUseSamePixelCoordinatesAsCamera() throws Exception {
        ScaledPanel panel = new ScaledPanel();
        panel.scale = 1.25;
        SwingUtilities.invokeAndWait(() -> panel.setSize(800, 600));
        ViewPort view = new ViewPort("input", new Camera(1, 1));
        panel.attachTo(false, view);
        panel.initialize(new RenderManager(new NullRenderer()), view);
        panel.onFrameEnd();
        AwtMouseInput mouse = new AwtMouseInput();
        mouse.setInputSource(panel);
        MouseMotionEvent[] motion = new MouseMotionEvent[1];
        MouseButtonEvent[] click = new MouseButtonEvent[1];
        mouse.setInputListener(new RawInputListener() {
            public void beginInput() { }
            public void endInput() { }
            public void onJoyAxisEvent(JoyAxisEvent event) { }
            public void onJoyButtonEvent(JoyButtonEvent event) { }
            public void onMouseMotionEvent(MouseMotionEvent event) { motion[0] = event; }
            public void onMouseButtonEvent(MouseButtonEvent event) { click[0] = event; }
            public void onKeyEvent(KeyInputEvent event) { }
            public void onTouchEvent(TouchEvent event) { }
        });
        mouse.mouseMoved(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, 400, 300, 0, false));
        mouse.mousePressed(new MouseEvent(panel, MouseEvent.MOUSE_PRESSED, 0, 0, 400, 300, 1, false, MouseEvent.BUTTON1));
        mouse.update();
        assertEquals(500, motion[0].getX());
        assertEquals(375, motion[0].getY());
        assertEquals(500, click[0].getX());
        assertEquals(375, click[0].getY());
    }
}
