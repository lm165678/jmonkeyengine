package com.jme3.environment.gi;

import com.jme3.light.*;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.shadow.ShadowManager;
import java.util.*;

/** Capture-owned policy using the same per-light shadows as runtime rendering. */
final class GiCaptureShadows {
    private GiCaptureShadows() {
    }

    static void configure(RenderManager manager, Spatial scene, ViewPort capture, GiSceneBaker.Settings settings) {
        ShadowManager shadows = manager.getShadowManager(capture);
        shadows.setBakedShadowsEnabled(false);
        shadows.setEnabled(settings.captureShadows);
        shadows.setByteBudget(Math.max(1, settings.shadowByteBudget));
        if (!settings.captureShadows) {
            return;
        }
        Set<Light> lights = Collections.newSetFromMap(new IdentityHashMap<Light, Boolean>());
        scene.depthFirstTraversal(spatial -> {
            for (Light light : spatial.getWorldLightList()) {
                if (light.isEnabled() && light.isCastShadow()
                        && (light instanceof DirectionalLight || light instanceof PointLight || light instanceof SpotLight)) {
                    lights.add(light);
                }
            }
        });
        if (lights.size() > settings.maxShadowLights) {
            throw new IllegalArgumentException("Capture shadow-light budget exceeded");
        }
        for (Light light : lights) {
            // Runtime shadow-strength diagnostics must not feed unoccluded light into GI.
            shadows.getSettings(light).setStrength(1f);
            shadows.getSettings(light).setMapSize(settings.shadowMapSize);
            shadows.getSettings(light).setShadowDistance(settings.far);
        }
    }
}
