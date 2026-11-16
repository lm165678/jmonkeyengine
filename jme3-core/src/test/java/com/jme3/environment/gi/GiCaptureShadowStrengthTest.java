package com.jme3.environment.gi;

import com.jme3.light.DirectionalLight;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.NullRenderer;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiCaptureShadowStrengthTest {
    @Test
    public void captureUsesFullOcclusionWithoutChangingSavedLight() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort capture = new ViewPort("capture", new Camera(16, 16));
        DirectionalLight sun = new DirectionalLight();
        sun.getShadowSettings().setStrength(0.2f);
        Node root = new Node();
        root.addLight(sun);
        root.updateGeometricState();
        GiCaptureShadows.configure(manager, root, capture, new GiSceneBaker.Settings());
        assertEquals(1f, manager.getShadowManager(capture).getSettings(sun).getStrength(), 0f);
        assertEquals(0.2f, sun.getShadowSettings().getStrength(), 0f);
        manager.getShadowManager(capture).close();
    }
}
