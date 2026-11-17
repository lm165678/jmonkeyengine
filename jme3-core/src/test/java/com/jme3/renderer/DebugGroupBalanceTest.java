package com.jme3.renderer;

import com.jme3.light.LightList;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import com.jme3.system.NullRenderer;
import com.jme3.renderer.queue.RenderQueue;
import org.junit.Test;
import static org.junit.Assert.*;

public class DebugGroupBalanceTest {
    private static final class RecordingRenderer extends NullRenderer {
        int depth;
        int pushes;

        @Override
        public void pushDebugGroup(String name) {
            depth++;
            pushes++;
        }

        @Override
        public void popDebugGroup() {
            assertTrue(depth > 0);
            depth--;
        }
    }

    @Test
    public void failedMaterialDoesNotLeakGeometryOrQueueGroups() {
        RecordingRenderer renderer = new RecordingRenderer();
        RenderManager manager = new RenderManager(renderer);
        Camera camera = new Camera(32, 32);
        manager.setCamera(camera, false);
        RuntimeException failure = new RuntimeException("simulated material failure");
        Geometry geometry = new Geometry("broken", new Box(1, 1, 1));
        geometry.setMaterial(new Material() {
            @Override
            public void render(Geometry geometry, LightList lights, RenderManager manager) {
                throw failure;
            }
        });
        geometry.updateGeometricState();
        RenderQueue queue = new RenderQueue();
        queue.addToQueue(geometry, RenderQueue.Bucket.Opaque);
        for (int i = 0; i < 100; i++) {
            try {
                if (i % 2 == 0) {
                    queue.renderQueue(RenderQueue.Bucket.Opaque, manager, camera, false);
                } else {
                    queue.renderShadowQueue(queue.getList(RenderQueue.Bucket.Opaque), manager, camera, false);
                }
                fail("Expected the original material error");
            } catch (RuntimeException actual) {
                assertSame(failure, actual);
            }
            assertEquals(0, renderer.depth);
        }
        assertEquals(200, renderer.pushes);
    }
}
