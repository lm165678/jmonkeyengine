package com.jme3.post;

import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.system.NullRenderer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import org.junit.Test;
import static org.junit.Assert.*;

public class FilterGraphContextTest {
    @Test
    public void colorDimensionsUseSceneSizeAndEquivalentDefinitionsReuseStorage() {
        RenderManager manager = new RenderManager(new NullRenderer());
        ViewPort viewport = new ViewPort("Test", new Camera(7, 9));
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<Texture2D> depth = graph.importResource("Depth", Texture2D.class, () -> null);
            FilterGraphContext context = new FilterGraphContext(graph, null, "Effect", 321, 239, depth);
            ResourceHandle<RenderTarget> first = context.createColor("First", .5f, Image.Format.RGBA16F);
            ResourceHandle<RenderTarget> second = context.createColor("Second", .5f, Image.Format.RGBA16F);
            RenderTarget[] allocations = new RenderTarget[2];
            FrameGraph.PassBuilder writer = graph.addPass("First", pass -> {
                allocations[0] = pass.get(first);
                assertEquals(161, allocations[0].getFrameBuffer().getWidth());
                assertEquals(120, allocations[0].getFrameBuffer().getHeight());
            }).write(first).sideEffect();
            graph.addPass("Second", pass -> {
                allocations[1] = pass.get(second);
            }).write(second).sideEffect().after(writer);
            graph.execute(manager, viewport);
            assertSame(allocations[0], allocations[1]);
        }
    }

    @Test
    public void invalidScalesAreRejectedBeforeAllocation() {
        try (FrameGraph graph = new FrameGraph()) {
            FilterGraphContext context = new FilterGraphContext(graph, null, "Effect", 321, 239, null);
            for (float scale : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE}) {
                try {
                    context.createColor("Invalid", scale, Image.Format.RGBA8);
                    fail("Accepted invalid scale " + scale);
                } catch (IllegalArgumentException expected) {
                    // A malformed effect must fail before allocating GPU storage.
                }
            }
        }
    }
}
