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
            assertEquals(1f, light.getShadowSettings().getStrength(), 0f);
            assertEquals(1f, roundTrip(light).getShadowSettings().getStrength(), 0f);
            light.getShadowSettings().setStrength(0.4f);
            assertEquals(0.4f, light.clone().getShadowSettings().getStrength(), 0f);
            assertEquals(0.4f, roundTrip(light).getShadowSettings().getStrength(), 0f);
            assertTrue(light.isCastShadow());
            assertTrue(roundTrip(light).isCastShadow());
            light.setCastShadow(false);
            assertTrue(light.isEnabled());
            assertFalse(light.clone().isCastShadow());
            assertFalse(roundTrip(light).isCastShadow());
        }
    }

    @Test
    public void invalidShadowStrengthIsRejected() {
        for (float value : new float[]{-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            try {
                new DirectionalLight().getShadowSettings().setStrength(value);
                fail("Accepted invalid shadow strength");
            } catch (IllegalArgumentException expected) {
                // Invalid values must not reach shaders or persisted assets.
            }
        }
    }

    private Light roundTrip(Light light) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(light, bytes);
        return (Light) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
