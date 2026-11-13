package com.jme3.renderer.deferred;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.MatParamOverride;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.shader.VarType;
import com.jme3.system.NullRenderer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeferredOverrideCompatibilityTest {
    @Test
    void physicalTileCameraPreservesLogicalCameraAndProjection() {
        RenderManager manager = new RenderManager(new NullRenderer());
        Camera logical = new Camera(64, 48);
        logical.setFrustumPerspective(60f, 4f / 3f, 0.1f, 100f);
        ViewPort viewport = manager.createMainView("hidpi", logical);
        viewport.setOutputFrameBuffer(new com.jme3.texture.FrameBuffer(128, 96, 1));
        DeferredRenderContext context = new DeferredRenderContext(manager, viewport, null, null);
        Camera physical = context.camera();
        assertEquals(128, physical.getWidth());
        assertEquals(96, physical.getHeight());
        assertEquals(logical.getProjectionMatrix(), physical.getProjectionMatrix());
        assertEquals(64, logical.getWidth());
        assertEquals(48, logical.getHeight());
    }

    @Test
    void internalDrawBufferRoutingAllowsDeferredButApplicationOverridesStillFallback() {
        NullRenderer renderer = new NullRenderer();
        renderer.initialize();
        RenderManager manager = new RenderManager(renderer);
        ViewPort viewport = manager.createMainView("main", new Camera(64, 48));
        viewport.setClearFlags(true, true, true);
        DeferredPipeline pipeline = new DeferredPipeline(new DesktopAssetManager(true));
        pipeline.initialize(manager, viewport);
        try {
            pipeline.render(viewport.getQueue());
            assertEquals("", pipeline.getFallbackReason());

            MatParamOverride override = new MatParamOverride(VarType.Float, "Shininess", 8f);
            manager.addForcedMatParam(override);
            pipeline.render(viewport.getQueue());
            assertEquals("Viewport or render overrides require forward rendering",
                    pipeline.getFallbackReason());

            manager.removeForcedMatParam(override);
            manager.setPassDrawBufferTargetIdToShaders(false);
            pipeline.render(viewport.getQueue());
            assertEquals("", pipeline.getFallbackReason());
        } finally {
            pipeline.cleanup();
        }
    }
}
