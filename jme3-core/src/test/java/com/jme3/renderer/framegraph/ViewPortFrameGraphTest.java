package com.jme3.renderer.framegraph;

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewPortFrameGraphTest {
    private static class Processor implements SceneProcessor {
        final List<String> events;
        ViewPort viewport;
        boolean fail;
        final FrameBuffer output = new FrameBuffer(64, 48, 1);

        Processor(List<String> events) {
            this.events = events;
        }

        public void initialize(RenderManager manager, ViewPort viewport) {
            this.viewport = viewport;
            events.add("initialize");
        }

        public boolean isInitialized() {
            return viewport != null;
        }

        public void preFrame(float tpf) {
            events.add("pre");
            viewport.setOutputFrameBuffer(output);
        }

        public void postQueue(RenderQueue queue) {
            events.add("shadow");
            if (fail) {
                throw new IllegalStateException("shadow failure");
            }
        }

        public void postFrame(FrameBuffer output) {
            assertSame(this.output, output);
            events.add("post");
        }

        public void reshape(ViewPort viewport, int width, int height) {
        }

        public void cleanup() {
            viewport = null;
        }

        public void setProfiler(AppProfiler profiler) {
        }
    }

    @Test
    public void preservesCallbacksAndDynamicOutputAcrossFramesAndRemoval() {
        List<String> events = new ArrayList<>();
        RenderManager manager = new RenderManager(new NullRenderer()) {
            @Override
            public void flushQueue(ViewPort viewport) {
                events.add("geometry");
                super.flushQueue(viewport);
            }

            @Override
            public void renderTranslucentQueue(ViewPort viewport) {
                events.add("translucent");
                super.renderTranslucentQueue(viewport);
            }
        };
        ViewPort viewport = manager.createMainView("test", new Camera(64, 48));
        Processor processor = new Processor(events);
        viewport.addProcessor(processor);
        manager.renderViewPort(viewport, 0);
        assertEquals(Arrays.asList("initialize", "pre", "shadow", "geometry", "post", "translucent"), events);
        FrameGraph graph = manager.getViewPortFrameGraph(viewport);
        List<String> order = graph.getExecutionOrder();
        assertTrue(order.indexOf("ShadowAndPostQueue") < order.indexOf("OpaquePipeline"));
        assertTrue(order.indexOf("ForwardAndBuckets/CompatibilityFlush") < order.indexOf("PostProcessing"));
        assertTrue(order.stream().anyMatch(name -> name.endsWith("/PostQueue")));
        assertTrue(order.stream().anyMatch(name -> name.endsWith("/PostFrame")));
        events.clear();
        manager.renderViewPort(viewport, 0);
        assertSame(graph, manager.getViewPortFrameGraph(viewport));
        assertEquals(Arrays.asList("pre", "shadow", "geometry", "post", "translucent"), events);
        manager.removeMainView(viewport);
        assertNull(manager.getViewPortFrameGraph(viewport));
    }

    @Test
    public void failedStageStopsDownstreamAndNextFrameCanRecover() {
        List<String> events = new ArrayList<>();
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort viewport = manager.createMainView("test", new Camera(64, 48));
        Processor processor = new Processor(events);
        viewport.addProcessor(processor);
        processor.fail = true;
        try {
            manager.renderViewPort(viewport, 0);
            fail("Expected shadow failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ShadowAndPostQueue"));
        }
        assertFalse(events.contains("post"));
        processor.fail = false;
        manager.renderViewPort(viewport, 0);
        assertTrue(events.contains("post"));
        manager.cleanupRenderPaths();
    }
}
