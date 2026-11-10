package com.jme3.renderer.deferred;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadingModelRegistryTest {
    @Test
    public void onlySourceChangesInvalidateProgramsAndBuiltinsAreReserved() {
        ShadingModelRegistry registry = new ShadingModelRegistry();
        assertFalse(registry.supports(0));
        assertTrue(registry.supports(1));
        assertTrue(registry.supports(2));
        assertTrue(registry.supports(3));
        assertFalse(registry.supports(7));
        registry.register(7, "source");
        long version = registry.getVersion();
        registry.register(7, "source");
        assertEquals(version, registry.getVersion());
        registry.register(7, "updated");
        assertTrue(registry.getVersion() > version);
        try {
            registry.register(3, "source");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            registry.getSources().put(8, "source");
            fail();
        } catch (UnsupportedOperationException expected) {
        }
    }

    @Test
    public void materialDefinitionsResolveWithoutApplicationLoadingOrRegistration() {
        final int[] loads = {0};
        com.jme3.asset.DesktopAssetManager assets = new com.jme3.asset.DesktopAssetManager(false) {
            @Override
            public Object loadAsset(String path) {
                loads[0]++;
                return "vec3 deferredLighting(vec4 a, vec4 n, vec4 s, vec4 extra, vec3 v, vec3 l, vec3 c) { return a.rgb*c; }";
            }
        };
        com.jme3.material.MaterialDef first = new com.jme3.material.MaterialDef(assets, "first");
        first.setDeferredLightingShader("auto.frag");
        com.jme3.material.MaterialDef second = new com.jme3.material.MaterialDef(assets, "second");
        second.setDeferredLightingShader("auto.frag");
        ShadingModelRegistry registry = new ShadingModelRegistry();
        int id = registry.resolve(first, assets);
        assertTrue(id >= 4);
        assertEquals(0, first.getShadingId());
        assertEquals(id, registry.resolve(first, assets));
        assertEquals(id, registry.resolve(second, assets));
        assertEquals(1, loads[0]);
        assertTrue(registry.getSources().get(id).contains("deferredModel" + id + "("));
        try {
            registry.register(id, "replace owned model");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        first.setShadingId(2);
        try {
            registry.resolve(first, assets);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }
    @Test
    public void materialExplicitIdsCannotSilentlyReplaceExistingModels() {
        com.jme3.asset.DesktopAssetManager assets = new com.jme3.asset.DesktopAssetManager(false) {
            @Override
            public Object loadAsset(String path) {
                return "vec3 deferredLighting() { return vec3(1.0); }";
            }
        };
        com.jme3.material.MaterialDef definition =
            new com.jme3.material.MaterialDef(assets, "custom");
        definition.setShadingId(7);
        definition.setDeferredLightingShader("custom.frag");
        ShadingModelRegistry registry = new ShadingModelRegistry();
        registry.register(7, "different");
        try {
            registry.resolve(definition, assets);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void fullSurfaceMaterialOwnsItsGBufferDecoding() {
        com.jme3.asset.DesktopAssetManager assets = new com.jme3.asset.DesktopAssetManager(false) {
            @Override
            public Object loadAsset(String path) {
                return "vec4 deferredSurfaceShading() { return getGBuffer4(); }";
            }
        };
        com.jme3.material.MaterialDef definition =
            new com.jme3.material.MaterialDef(assets, "custom layout");
        definition.setDeferredLightingShader("independent.frag");
        ShadingModelRegistry registry = new ShadingModelRegistry();
        int id = registry.resolve(definition, assets);
        String source = registry.getSources().get(id);
        assertTrue(source.contains("deferredSurfaceModel" + id + "()"));
        assertTrue(source.contains("getGBuffer4()"));
        assertFalse(source.contains("record3.rgb * AmbientColor"));
    }
}
