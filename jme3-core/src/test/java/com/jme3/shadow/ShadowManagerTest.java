package com.jme3.shadow;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.light.DirectionalLight;
import com.jme3.renderer.*;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.system.NullRenderer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowManagerTest {
    @Test
    public void settingsBelongToLightIdentityAndViewport() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort main = new ViewPort("main", new Camera(32, 32));
        ViewPort reflection = new ViewPort("reflection", new Camera(32, 32));
        DirectionalLight a = new DirectionalLight();
        DirectionalLight b = new DirectionalLight();
        a.setName("same-name");
        b.setName("same-name");
        ShadowManager shadows = manager.getShadowManager(main);
        assertTrue(shadows.isEnabled());
        assertSame(shadows.getSettings(a), shadows.getSettings(a));
        shadows.getSettings(a).setEnabled(false);
        assertTrue(shadows.getSettings(b).isEnabled());
        assertTrue(manager.getShadowManager(reflection).getSettings(a).isEnabled());
        assertTrue(a.isCastShadow());
        manager.cleanupRenderPaths();
    }

    @Test
    public void unsupportedHardwareHasQueryableFallback() {
        RenderManager manager = new RenderManager(new NullRenderer() {
            @Override
            public java.util.EnumSet<Caps> getCaps() {
                return java.util.EnumSet.noneOf(Caps.class);
            }
        });
        ShadowManager shadows = manager.getShadowManager(new ViewPort("test", new Camera(32, 32)));
        try (FrameGraph graph = new FrameGraph()) {
            shadows.appendPasses(graph);
            assertNotNull(shadows.getFallbackReason());
        }
        manager.cleanupRenderPaths();
    }

    @Test
    public void legacyRendererHonorsSceneShadowGate() {
        DirectionalLight light = new DirectionalLight();
        DirectionalLightShadowRenderer renderer = new DirectionalLightShadowRenderer(new DesktopAssetManager(true), 64, 1);
        renderer.setLight(light);
        Camera camera = new Camera(32, 32);
        assertTrue(renderer.checkCulling(camera));
        light.setCastShadow(false);
        assertFalse(renderer.checkCulling(camera));
        light.setCastShadow(true);
        assertTrue(renderer.checkCulling(camera));
        light.setEnabled(false);
        assertFalse(renderer.checkCulling(camera));
    }
}
