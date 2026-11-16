package com.jme3.environment.gi;

import com.jme3.environment.SkyLightControl;
import com.jme3.light.LightProbe;
import com.jme3.scene.Node;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkyLightLifecycleTest {
    @Test
    public void preloadWithoutLightsDoesNotDisableLaterEnvironmentDraws() {
        assertFalse(com.jme3.environment.EnvironmentLighting.available(null));
        Node node = new Node();
        assertFalse(com.jme3.environment.EnvironmentLighting.available(node.getLocalLightList()));
        LightProbe probe = new LightProbe();
        node.addLight(probe);
        assertFalse(com.jme3.environment.EnvironmentLighting.available(node.getLocalLightList()));
        probe.setReady(true);
        assertTrue(com.jme3.environment.EnvironmentLighting.available(node.getLocalLightList()));
        probe.setEnabled(false);
        assertFalse(com.jme3.environment.EnvironmentLighting.available(node.getLocalLightList()));
    }

    @Test
    public void skyTracksParentAndDisableWithoutOrphanLights() {
        Node root = new Node();
        Node other = new Node();
        Node sky = new Node();
        SkyLightControl control = new SkyLightControl();
        sky.addControl(control);
        root.attachChild(sky);
        assertEquals(1, root.getLocalLightList().size());
        assertSame(control.getLight(), root.getLocalLightList().get(0));
        control.setEnabled(false);
        assertEquals(0, root.getLocalLightList().size());
        control.setEnabled(true);
        assertEquals(1, root.getLocalLightList().size());
        other.attachChild(sky);
        assertEquals(0, root.getLocalLightList().size());
        assertEquals(1, other.getLocalLightList().size());
        sky.removeFromParent();
        assertEquals(0, other.getLocalLightList().size());
    }

    @Test
    public void reflectionRoleSurvivesSerializationWithoutChangingLegacyDefault() throws IOException {
        LightProbe probe = new LightProbe();
        assertTrue(probe.isDiffuseEnabled());
        probe.setDiffuseEnabled(false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(probe, bytes);
        LightProbe loaded = (LightProbe) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
        assertFalse(loaded.isDiffuseEnabled());
    }
}
