package com.jme3.renderer.deferred;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadingModelRegistryTest {
    @Test public void onlySourceChangesInvalidateProgramsAndBuiltinsAreReserved() {
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
        try { registry.register(3, "source"); fail(); } catch (IllegalArgumentException expected) { }
        try { registry.getSources().put(8, "source"); fail(); } catch (UnsupportedOperationException expected) { }
    }
}
