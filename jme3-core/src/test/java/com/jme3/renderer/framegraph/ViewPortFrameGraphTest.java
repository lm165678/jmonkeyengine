package com.jme3.renderer.framegraph;

import com.jme3.math.Vector2f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Limits;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.renderer.temporal.TemporalScene;
import com.jme3.system.NullRenderer;
import com.jme3.system.TestUtil;
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

    @Test
    public void schedulesVelocityOnlyWhenPreFrameInstallsMotionInputs() {
        List<String> events = new ArrayList<>();
        RenderManager manager = new RenderManager(new NullRenderer());
        Camera camera = new Camera(64, 48);
        ViewPort view = manager.createMainView("test", camera);
        try (TemporalScene motion = new TemporalScene(TestUtil.createAssetManager(), manager, view)) {
            class MotionProcessor extends Processor {
                boolean active;

                MotionProcessor(List<String> events) {
                    super(events);
                }

                @Override
                public void preFrame(float tpf) {
                    super.preFrame(tpf);
                    if (active) {
                        viewport.setTemporalScene(motion);
                        motion.begin(64, 48, Vector2f.ZERO);
                    }
                }
            }
            MotionProcessor processor = new MotionProcessor(events);
            view.addProcessor(processor);

            manager.renderViewPort(view, 0);
            FrameGraph graph = manager.getViewPortFrameGraph(view);
            assertFalse(graph.getExecutedPasses().stream().anyMatch(name -> name.contains("Velocity")));
            assertNull(motion.getVelocity());

            processor.active = true;
            manager.renderViewPort(view, 0);
            List<String> passes = graph.getExecutedPasses();
            int opaque = passes.indexOf("PostProcessing/OpaqueVelocityPass");
            int transparent = passes.indexOf("PostProcessing/TranslucentVelocityPass");
            int postFrame = passes.indexOf("PostProcessing/Processors/Processor[0]:MotionProcessor/PostFrame");
            assertTrue(opaque > passes.indexOf("ForwardAndBuckets"));
            assertTrue(transparent > opaque);
            assertTrue(postFrame > transparent);
            assertNotNull(motion.getVelocity());
            assertSame(camera, view.getCamera());
            motion.commit();
            manager.renderViewPort(view, 0);
            assertFalse(motion.isReset());
            assertSame(camera, view.getCamera());

            // A diagnostic output can prevent an active effect from running
            // without calling its disable/cleanup hook.
            processor.active = false;
            manager.renderViewPort(view, 0);
            assertFalse(graph.getExecutedPasses().stream().anyMatch(name -> name.contains("Velocity")));
            assertNull(view.getTemporalScene());
            assertTrue(motion.isReset());
            assertSame(camera, view.getCamera());

            processor.active = true;
            manager.renderViewPort(view, 0);
            assertTrue(graph.getExecutedPasses().contains("PostProcessing/OpaqueVelocityPass"));
            assertSame(motion, view.getTemporalScene());
            assertSame(camera, view.getCamera());
        } finally {
            manager.removeMainView(view);
        }
    }

    @Test
    public void switchesMotionOutputWhenTheMainPassHasTooFewAttachments() {
        NullRenderer renderer = new NullRenderer();
        renderer.getLimits().put(Limits.FrameBufferMrtAttachments, 5);
        RenderManager manager = new RenderManager(renderer);
        ViewPort view = manager.createMainView("test", new Camera(64, 48));
        try (TemporalScene motion = new TemporalScene(TestUtil.createAssetManager(), manager, view)) {
            manager.setRenderPath(RenderPath.TiledDeferredGPU);
            motion.configure(true, true, .5f);
            assertFalse(motion.isBasePass());
            assertFalse(motion.isGBufferPass());

            view.setRenderPath(RenderPath.Forward);
            motion.configure(true, true, .5f);
            assertTrue(motion.isBasePass());
            assertFalse(motion.isGBufferPass());

            view.setRenderPath(RenderPath.Inherit);
            renderer.getLimits().put(Limits.FrameBufferMrtAttachments, 6);
            motion.configure(true, true, .5f);
            assertTrue(motion.isBasePass());
            assertTrue(motion.isGBufferPass());

            motion.configure(false, true, .5f);
            assertFalse(motion.isBasePass());
            assertFalse(motion.isGBufferPass());
        } finally {
            manager.removeMainView(view);
        }
    }
}
