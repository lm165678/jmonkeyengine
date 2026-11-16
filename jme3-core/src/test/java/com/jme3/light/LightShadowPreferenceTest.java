package com.jme3.light;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class LightShadowPreferenceTest {
    @Test
    public void preferenceSurvivesCloneAndBinaryRoundTrip() throws Exception {
        for (Light light : new Light[]{new DirectionalLight(), new PointLight(), new SpotLight()}) {
            assertTrue(light.isCastShadow());
            assertTrue(roundTrip(light).isCastShadow());
            light.setCastShadow(false);
            assertTrue(light.isEnabled());
            assertFalse(light.clone().isCastShadow());
            assertFalse(roundTrip(light).isCastShadow());
        }
    }

    private Light roundTrip(Light light) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(light, bytes);
        return (Light) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
