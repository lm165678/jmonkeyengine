package com.jme3.post;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.scene.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Scene persistence must not replace the independent probe residency policy. */
public class HalfResolutionProbeGiTest {
    @Test
    public void newSamplingDefaultsAndHistoricalFalseRoundTrip() throws Exception {
        Node scene = new Node("world");
        WorldSettings world = new WorldSettings();
        assertTrue(world.isSingleProbeVisibility());
        assertTrue(world.isOctahedralProbeIrradiance());
        scene.addControl(world);
        WorldSettings loaded = roundTrip(scene).getControl(WorldSettings.class);
        assertTrue(loaded.isSingleProbeVisibility());
        assertTrue(loaded.isOctahedralProbeIrradiance());
        // False fields are omitted using the historical serialization default.
        world.setSingleProbeVisibility(false);
        world.setOctahedralProbeIrradiance(false);
        loaded = roundTrip(scene).getControl(WorldSettings.class);
        assertFalse(loaded.isSingleProbeVisibility());
        assertFalse(loaded.isOctahedralProbeIrradiance());
    }

    @Test
    public void roundTripAndRestorationPreserveStreamingPolicy() throws Exception {
        Node scene = new Node("world");
        WorldSettings world = new WorldSettings();
        assertFalse(world.isHalfResolutionProbeGi());
        assertTrue(world.isProbeGiFullFallback());
        world.setGiStreaming(new WorldSettings.GiStreaming(37, 1234, 32));
        world.setHalfResolutionProbeGi(true);
        world.setProbeGiFullFallback(false);
        scene.addControl(world);
        Node copy = roundTrip(scene);
        WorldSettings loaded = copy.getControl(WorldSettings.class);
        assertTrue(loaded.isHalfResolutionProbeGi());
        assertFalse(loaded.isProbeGiFullFallback());
        loaded.setHalfResolutionProbeGi(false);
        loaded.setProbeGiFullFallback(true);
        loaded = roundTrip(copy).getControl(WorldSettings.class);
        assertFalse(loaded.isHalfResolutionProbeGi());
        assertTrue(loaded.isProbeGiFullFallback());
        assertEquals(37f, loaded.getGiStreaming().getPreloadDistance(), 0f);
        assertEquals(1234, loaded.getGiStreaming().getMaxFineProbes());
        assertEquals(32, loaded.getGiStreaming().getAtlasMiB());
    }

    private Node roundTrip(Node node) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(node, bytes);
        return (Node) BinaryImporter.getInstance().load(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
