package com.jme3.environment.gi;

import com.jme3.light.Light;
import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Internal stable snapshot; queried for baking/editor status, never per draw. */
final class GiSkyState {
    private GiSkyState() {
    }

    static String capture(Spatial scene) {
        StringBuilder state = new StringBuilder("sky-v1;");
        scene.depthFirstTraversal(spatial -> {
            // Include scope and order: moving a sky between scopes changes lighting.
            state.append('[');
            for (Light light : spatial.getLocalLightList()) {
                if (light instanceof SkyLight) {
                    SkyLight sky = (SkyLight) light;
                    state.append(sky.isEnabled()).append(':').append(sky.isReady())
                            .append(':').append(sky.isDiffuseEnabled()).append(':')
                            .append(Float.floatToIntBits(sky.getIntensity())).append(':')
                            .append(sky.getCaptureRevision()).append(':');
                    if (sky.getShCoeffs() != null) {
                        for (Vector3f coefficient : sky.getShCoeffs()) {
                            state.append(coefficient).append(';');
                        }
                    }
                }
            }
            state.append(']');
        });
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(state.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) {
                result.append(String.format("%02x", value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
