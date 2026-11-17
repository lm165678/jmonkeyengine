package com.jme3.renderer;

import com.jme3.material.Material;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.system.NullRenderer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Verifies that rendering failures do not leak nested OpenGL debug groups.
 */
public class RenderDebugGroupTest {

    @Test
    public void opaqueQueuePopsGeometryAndBucketGroupsAfterFailure() {
        RecordingRenderer renderer = new RecordingRenderer();
        RenderManager manager = new RenderManager(renderer);
        RenderQueue queue = new RenderQueue();
        queue.addToQueue(failingGeometry(), RenderQueue.Bucket.Opaque);

        assertThrows(IllegalStateException.class,
                () -> queue.renderQueue(RenderQueue.Bucket.Opaque, manager, new Camera(80, 60)));

        assertEquals(0, renderer.depth);
        assertEquals(List.of("push:Opaque", "push:broken", "pop", "pop"), renderer.events);
    }

    @Test
    public void shadowQueuePopsGeometryAndShadowGroupsAfterFailure() {
        RecordingRenderer renderer = new RecordingRenderer();
        RenderManager manager = new RenderManager(renderer);
        RenderQueue queue = new RenderQueue();
        queue.addToQueue(failingGeometry(), RenderQueue.Bucket.Opaque);
        GeometryList list = queue.getList(RenderQueue.Bucket.Opaque);

        assertThrows(IllegalStateException.class,
                () -> queue.renderShadowQueue(list, manager, new Camera(80, 60), false));

        assertEquals(0, renderer.depth);
        assertEquals(List.of("push:ShadowQueue", "push:broken", "pop", "pop"), renderer.events);
    }

    private static Geometry failingGeometry() {
        Geometry geometry = new Geometry("broken", new Mesh());
        geometry.setMaterial(new Material() {
            @Override
            public void render(Geometry geometry, com.jme3.light.LightList lights, RenderManager manager) {
                throw new IllegalStateException("expected test failure");
            }
        });
        return geometry;
    }

    private static final class RecordingRenderer extends NullRenderer {
        private final List<String> events = new ArrayList<>();
        private int depth;

        @Override
        public void pushDebugGroup(String name) {
            depth++;
            events.add("push:" + name);
        }

        @Override
        public void popDebugGroup() {
            depth--;
            events.add("pop");
        }
    }
}
