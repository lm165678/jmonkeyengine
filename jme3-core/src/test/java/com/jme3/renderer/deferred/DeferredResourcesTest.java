package com.jme3.renderer.deferred;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.shader.bufferobject.BufferObject;
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
        void register(NativeObject object) {
            object.setId(nextId++);
            objects.registerObject(object);
        }
        void registerTargets(DeferredResources resources) {
            register(resources.gbuffer);
            for (com.jme3.texture.Texture texture : resources.targets) {
                register(texture.getImage());
            }
        }
        void flush() {
            objects.deleteUnused(this);
        }
        final Set<Object> deleted =
            Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        private void deleted(NativeObject object) {
            assertTrue("Resource deleted twice", deleted.add(object));
            object.resetObject();
        }
        @Override
        public void deleteImage(Image image) {
            deleted(image);
        }
        @Override
        public void deleteFrameBuffer(FrameBuffer buffer) {
            deleted(buffer);
        }
        @Override
        public void deleteBuffer(BufferObject buffer) {
            deleted(buffer);
        }
        @Override
        public void deleteShader(Shader shader) {
            deleted(shader);
        }
    }

    @Test
    public void borrowedTargetsAreNotDisposedAndOwnedBuffersReleaseOnce() {
        TrackingRenderer renderer = new TrackingRenderer();
        DeferredResources resources = new DeferredResources(null, renderer);
        com.jme3.renderer.framegraph.RenderTargetDefinition definition =
            new com.jme3.renderer.framegraph.RenderTargetDefinition(1f, 1, Image.Format.Depth24,
                Image.Format.RGBA32F, Image.Format.RGBA16F, Image.Format.RGBA16F,
                Image.Format.RGBA16F, Image.Format.RGBA16F);
        com.jme3.renderer.framegraph.RenderTarget target = definition.create(renderer, 65, 49);
        resources.borrowTargets(target);
        renderer.registerTargets(resources);
        resources.releaseTargets();
        renderer.flush();
        assertTrue(renderer.deleted.isEmpty());
        resources.borrowTargets(target);
        resources.ensureGpuIndices(40);
        renderer.register(resources.gpuIndices);
        BufferObject old = resources.gpuIndices;
        resources.ensureGpuIndices(40);
        assertSame(old, resources.gpuIndices);
        resources.ensureGpuIndices(80);
        renderer.flush();
        assertTrue(renderer.deleted.contains(old));
        renderer.register(resources.gpuIndices);
        resources.cleanup();
        renderer.flush();
        assertEquals(2, renderer.deleted.size());
        assertNull(resources.gbuffer);
        assertNull(resources.targets[0]);
        assertNull(resources.gpuIndices);
        resources.cleanup();
        renderer.flush();
        assertEquals(2, renderer.deleted.size());
        definition.destroy(target);
        renderer.flush();
        assertEquals(9, renderer.deleted.size());
        renderer.objects.deleteAllObjects(renderer);
        assertEquals(9, renderer.deleted.size());
        try {
            resources.ensureGpuIndices(8);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void modelChangesInvalidateLightingButKeepComputeProgram() {
        TrackingRenderer renderer = new TrackingRenderer();
        DesktopAssetManager assets = new DesktopAssetManager(false) {
            @Override
            public Object loadAsset(String name) {
                return "// CUSTOM_MODEL_FUNCTIONS\nvec4 subfaceShading(int shadingId) {\n// CUSTOM_MODEL_DISPATCH\nreturn vec4(0.0);\n}";
            }
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

    @Test
    public void fullSurfaceDispatchHasNoLegacyLayoutAdapter() {
        java.util.Map<Integer, String> models = new java.util.TreeMap<>();
        models.put(9, "vec4 deferredSurfaceModel9() { return getGBuffer4(); }");
        models.put(10,
            "vec3 deferredModel10(vec4 a, vec4 b, vec4 c, vec4 d, vec3 v, vec3 l, vec3 color) { return a.rgb; }");
        String source = DeferredResources.assembleLightingSource(
            "// CUSTOM_MODEL_FUNCTIONS\n// CUSTOM_MODEL_DISPATCH", models);
        assertTrue(source.contains("if (shadingId == 9)"));
        assertTrue(source.contains("return deferredSurfaceModel9();"));
        assertFalse(source.contains("shadeLegacyModel9"));
        assertTrue(source.contains("shadeLegacyModel10"));
        assertFalse(source.contains("int(base.a)"));
        assertFalse(source.contains("// CUSTOM_MODEL_DISPATCH"));
    }
}
