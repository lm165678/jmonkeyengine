package com.jme3.environment.gi;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiWideSamplingTest {
    @Test
    public void defaultsAndRoundTrip() throws Exception {
        GiProbeVolume volume = new GiProbeVolume();
        assertFalse(volume.isWideProbeSampling());
        GiProbeVolume child = new GiProbeVolume();
        volume.attachChild(child);
        volume.setWideProbeSampling(true);
        assertTrue(child.isWideProbeSampling());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(volume, bytes);
        GiProbeVolume restored = (GiProbeVolume) BinaryImporter.getInstance()
                .load(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(restored.isWideProbeSampling());
        restored.setWideProbeSampling(false);
        assertFalse(((GiProbeVolume) restored.getChild(0)).isWideProbeSampling());
    }
}
