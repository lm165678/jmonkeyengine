package com.jme3.app;

import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.material.Material;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.SceneGraphVisitor;
import com.jme3.scene.control.AbstractControl;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneServiceCatalogTest {
    @Test
    public void disablingServicesReleasesCacheAndAllowsRediscovery() throws Exception {
        SceneServicesState services = new SceneServicesState();
        java.lang.reflect.Field field = SceneServicesState.class.getDeclaredField("catalog");
        field.setAccessible(true);
        SceneServiceCatalog catalog = (SceneServiceCatalog) field.get(services);
        Node root = new Node();
        GiProbeVolume oldVolume = new GiProbeVolume();
        root.attachChild(oldVolume);
        catalog.refresh(root);
        services.setEnabled(false);
        root.detachAllChildren();
        assertTrue(catalog.volumes.isEmpty());
        assertTrue(catalog.materials.isEmpty());
        assertTrue(catalog.controls.isEmpty());
        GiProbeVolume newVolume = new GiProbeVolume();
        root.attachChild(newVolume);
        services.setEnabled(true);
        catalog.refresh(root);
        assertFalse(catalog.volumes.contains(oldVolume));
        assertTrue(catalog.volumes.contains(newVolume));
        services.cleanup();
        assertTrue(catalog.volumes.isEmpty());
    }

    private static final class CountingNode extends Node {
        int scans;

        @Override
        public void depthFirstTraversal(SceneGraphVisitor visitor, DFSMode mode) {
            scans++;
            super.depthFirstTraversal(visitor, mode);
        }
    }

    @Test
    public void motionDoesNotRescanButMembershipAndMaterialChangesDo() {
        CountingNode root = new CountingNode();
        Node model = new Node();
        Geometry geometry = new Geometry();
        Material first = new Material();
        geometry.setMaterial(first);
        model.attachChild(geometry);
        root.attachChild(model);
        try (SceneServiceCatalog catalog = new SceneServiceCatalog()) {
            catalog.refresh(root);
            assertEquals(1, root.scans);
            model.setLocalTranslation(1, 2, 3);
            catalog.refresh(root);
            assertEquals(1, root.scans);
            assertTrue(catalog.materials.contains(first));

            GiProbeVolume volume = new GiProbeVolume();
            model.attachChild(volume);
            AbstractControl control = new AbstractControl() {
                @Override
                protected void controlUpdate(float tpf) {
                }

                @Override
                protected void controlRender(RenderManager manager, ViewPort viewport) {
                }
            };
            geometry.addControl(control);
            Material second = new Material();
            geometry.setMaterial(second);
            catalog.refresh(root);
            assertEquals(2, root.scans);
            assertTrue(catalog.volumes.contains(volume));
            assertTrue(catalog.controls.contains(control));
            assertFalse(catalog.materials.contains(first));
            assertTrue(catalog.materials.contains(second));

            geometry.removeControl(control);
            catalog.refresh(root);
            assertTrue(catalog.controls.isEmpty());
            model.removeFromParent();
            catalog.refresh(root);
            assertTrue(catalog.volumes.isEmpty());
            assertTrue(catalog.materials.isEmpty());
        }
    }
}
