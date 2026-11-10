package com.jme3.renderer.pipeline;

import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.system.NullRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RenderPipelineLifecycleTest {
    private static final class RecordingPipeline implements RenderPipeline {
        final List<String> events = new ArrayList<>();
        boolean initialized, failInitialization, failCleanup;
        @Override public void initialize(RenderManager manager, ViewPort viewport) {
            events.add("initialize");
            initialized = true;
            if (failInitialization) throw new IllegalStateException("initialization");
        }
        @Override public boolean isInitialized() { return initialized; }
        @Override public void reshape(int width, int height) { events.add("resize " + width + "x" + height); }
        @Override public void render(RenderQueue queue) { events.add("render"); }
        @Override public void cleanup() {
            events.add("cleanup");
            initialized = false;
            if (failCleanup) throw new IllegalStateException("cleanup");
        }
    }

    @Test public void installResizeReplaceAndRemoveAreOwnedByManager() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = manager.createMainView("main", new Camera(64, 48));
        RecordingPipeline first = new RecordingPipeline();
        manager.setPipeline(view, first);
        manager.setPipeline(view, first);
        assertTrue(first.events.isEmpty());
        manager.renderViewPort(view, 0);
        manager.renderViewPort(view, 0);
        manager.notifyReshape(81, 63);
        manager.renderViewPort(view, 0);
        RecordingPipeline second = new RecordingPipeline();
        manager.setPipeline(view, second);
        assertEquals(Arrays.asList("initialize", "render", "render", "resize 81x63", "render", "cleanup"), first.events);
        manager.renderViewPort(view, 0);
        manager.removeMainView("main");
        manager.cleanupRenderPaths();
        assertEquals(Arrays.asList("initialize", "render", "cleanup"), second.events);
        assertNull(manager.getPipeline(view));
    }

    @Test public void instanceCannotBeSharedOrInstalledIntoForeignViewport() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort a = manager.createMainView("a", new Camera(64, 48));
        ViewPort b = manager.createMainView("b", new Camera(64, 48));
        RecordingPipeline pipeline = new RecordingPipeline();
        manager.setPipeline(a, pipeline);
        try { manager.setPipeline(b, pipeline); fail(); } catch (IllegalArgumentException expected) { }
        try { manager.setPipeline(new ViewPort("foreign", new Camera(1, 1)), null); fail(); }
        catch (IllegalArgumentException expected) { }
        assertSame(pipeline, manager.getPipeline(a));
        assertNull(manager.getPipeline(b));
    }

    @Test public void failedInitializationCleansPartialResourcesAndPreservesCause() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = manager.createMainView("main", new Camera(64, 48));
        RecordingPipeline pipeline = new RecordingPipeline();
        pipeline.failInitialization = pipeline.failCleanup = true;
        manager.setPipeline(view, pipeline);
        try { manager.renderViewPort(view, 0); fail(); }
        catch (IllegalStateException error) {
            assertEquals("initialization", error.getMessage());
            assertEquals(1, error.getSuppressed().length);
        }
        assertNull(manager.getPipeline(view));
        assertEquals(Arrays.asList("initialize", "cleanup"), pipeline.events);
    }

    @Test public void cleanupFailureDoesNotStrandOtherViewports() {
        RenderManager manager = new RenderManager(new NullRenderer());
        RecordingPipeline first = new RecordingPipeline(), second = new RecordingPipeline();
        first.failCleanup = true;
        ViewPort a = manager.createMainView("a", new Camera(64, 48));
        ViewPort b = manager.createMainView("b", new Camera(64, 48));
        manager.setPipeline(a, first);
        manager.setPipeline(b, second);
        manager.renderViewPort(a, 0);
        manager.renderViewPort(b, 0);
        try { manager.cleanupRenderPaths(); fail(); } catch (IllegalStateException expected) { }
        assertEquals(Arrays.asList("initialize", "render", "cleanup"), first.events);
        assertEquals(Arrays.asList("initialize", "render", "cleanup"), second.events);
        assertNull(manager.getPipeline(a));
        assertNull(manager.getPipeline(b));
        manager.cleanupRenderPaths();
        assertEquals(3, second.events.size());
    }

    @Test public void secondManagerCannotBorrowOrDestroyAnInitializedInstance() {
        RenderManager first = new RenderManager(new NullRenderer());
        RenderManager second = new RenderManager(new NullRenderer());
        ViewPort a = first.createMainView("a", new Camera(64, 48));
        ViewPort b = second.createMainView("b", new Camera(64, 48));
        RecordingPipeline pipeline = new RecordingPipeline();
        first.setPipeline(a, pipeline);
        second.setPipeline(b, pipeline);
        first.renderViewPort(a, 0);
        try { second.renderViewPort(b, 0); fail(); } catch (IllegalStateException expected) { }
        second.cleanupRenderPaths();
        assertTrue(pipeline.isInitialized());
        assertEquals(Arrays.asList("initialize", "render"), pipeline.events);
        assertNull(second.getPipeline(b));
        first.cleanupRenderPaths();
        assertEquals(Arrays.asList("initialize", "render", "cleanup"), pipeline.events);
    }

    @Test public void nullReplacementRestoresOrdinaryForwardWithoutReinitializing() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort view = manager.createMainView("main", new Camera(64, 48));
        RecordingPipeline pipeline = new RecordingPipeline();
        manager.setPipeline(view, pipeline);
        manager.renderViewPort(view, 0);
        manager.setPipeline(view, null);
        manager.renderViewPort(view, 0);
        assertEquals(Arrays.asList("initialize", "render", "cleanup"), pipeline.events);
        assertNull(manager.getPipeline(view));
    }
}
