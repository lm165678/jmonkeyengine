package com.jme3.environment.gi;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiSmoothBlendingTest {
    @Test
    public void removedModeCannotBeEnabledOrRestored() throws Exception {
        GiProbeVolume root = new GiProbeVolume();
        assertFalse(root.isSmoothBlending());
        GiProbeVolume generated = new GiProbeVolume();
        generated.setUserData(GiAdaptiveProbeLayout.GENERATED, true);
        GiProbeVolume manual = new GiProbeVolume();
        root.attachChild(generated);
        root.attachChild(manual);
        root.setStabilizedBlending(true);
        root.setSmoothBlending(true);
        assertFalse(generated.isSmoothBlending());
        assertFalse(manual.isSmoothBlending());
        // Simulate an old scene that persisted the removed option as true.
        java.lang.reflect.Field legacy = GiProbeVolume.class.getDeclaredField("smoothBlending");
        legacy.setAccessible(true);
        legacy.setBoolean(root, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(root, bytes);
        GiProbeVolume restored = (GiProbeVolume) BinaryImporter.getInstance()
                .load(new ByteArrayInputStream(bytes.toByteArray()));
        assertFalse(restored.isSmoothBlending());
        assertFalse(((GiProbeVolume) restored.getChild(0)).isSmoothBlending());
        restored.setSmoothBlending(false);
        assertFalse(((GiProbeVolume) restored.getChild(0)).isSmoothBlending());
        assertTrue(restored.isStabilizedBlending());
    }
}
