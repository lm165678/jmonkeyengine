package com.jme3.renderer.deferred;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.shader.BufferObject;
import com.jme3.shader.Shader;
import com.jme3.system.NullRenderer;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.NativeObject;
import com.jme3.util.NativeObjectManager;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeferredResourcesTest {
    private static class TrackingRenderer extends NullRenderer {
        final NativeObjectManager objects = new NativeObjectManager();
        private int nextId = 1;
        void register(NativeObject object) { object.setId(nextId++); objects.registerObject(object); }
        void registerTargets(DeferredResources resources) {
            register(resources.gbuffer);
            for (com.jme3.texture.Texture texture : resources.targets) register(texture.getImage());
        }
        void flush() { objects.deleteUnused(this); }
        final Set<Object> deleted = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private void deleted(NativeObject object) {
            assertTrue("Resource deleted twice", deleted.add(object));
            object.resetObject();
        }
        @Override public void deleteImage(Image image) { deleted(image); }
        @Override public void deleteFrameBuffer(FrameBuffer buffer) { deleted(buffer); }
        @Override public void deleteBuffer(BufferObject buffer) { deleted(buffer); }
        @Override public void deleteShader(Shader shader) { deleted(shader); }
    }

    @Test public void resizeAndCleanupReleaseEachAllocationExactlyOnce() {
        TrackingRenderer renderer = new TrackingRenderer();
        DeferredResources resources = new DeferredResources(null, renderer);
        resources.ensureTargets(65, 49);
        renderer.registerTargets(resources);
        FrameBuffer old = resources.gbuffer;
        Image oldColor = resources.targets[0].getImage();
        resources.ensureTargets(65, 49);
        assertSame(old, resources.gbuffer);
        assertTrue(renderer.deleted.isEmpty());
        resources.ensureTargets(97, 73);
        renderer.flush();
        renderer.registerTargets(resources);
        assertTrue(renderer.deleted.contains(old));
        assertTrue(renderer.deleted.contains(oldColor));
        assertEquals(7, renderer.deleted.size());
        resources.ensureGpuIndices(40);
        renderer.register(resources.gpuIndices);
        BufferObject indices = resources.gpuIndices;
        resources.ensureGpuIndices(40);
        assertSame(indices, resources.gpuIndices);
        resources.ensureGpuIndices(80);
        renderer.flush();
        renderer.register(resources.gpuIndices);
        assertTrue(renderer.deleted.contains(indices));
        FrameBuffer last = resources.gbuffer;
        Image lastColor = resources.targets[0].getImage();
        resources.cleanup();
        renderer.flush();
        assertTrue(renderer.deleted.contains(last));
        assertTrue(renderer.deleted.contains(lastColor));
        int deletions = renderer.deleted.size();
        resources.cleanup();
        renderer.flush();
        assertEquals(deletions, renderer.deleted.size());
        assertNull(resources.gbuffer);
        assertNull(resources.gpuIndices);
        try { resources.ensureTargets(8, 8); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void modelChangesInvalidateLightingButKeepComputeProgram() {
        TrackingRenderer renderer = new TrackingRenderer();
        DesktopAssetManager assets = new DesktopAssetManager(false) {
            @Override public Object loadAsset(String name) { return "void main(){\n// CUSTOM_MODELS\n}"; }
        };
        DeferredResources resources = new DeferredResources(assets, renderer);
        ShadingModelRegistry registry = new ShadingModelRegistry();
        Shader lighting = resources.lightingShader(false, registry);
        Shader compute = resources.computeShader();
        renderer.register(lighting);
        renderer.register(compute);
        assertSame(lighting, resources.lightingShader(false, registry));
        registry.register(7, "vec3 deferredModel7(){return vec3(1.0);}");
        assertNotSame(lighting, resources.lightingShader(false, registry));
        renderer.flush();
        assertTrue(renderer.deleted.contains(lighting));
        assertFalse(renderer.deleted.contains(compute));
        assertSame(compute, resources.computeShader());
        resources.cleanup();
        renderer.flush();
        assertTrue(renderer.deleted.contains(compute));
    }
}
