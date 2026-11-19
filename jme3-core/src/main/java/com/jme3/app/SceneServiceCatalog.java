package com.jme3.app;

import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.SceneChangeListener;
import com.jme3.scene.control.Control;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Render-thread discovery cache; transforms do not change service membership. */
final class SceneServiceCatalog implements AutoCloseable {
    final Set<GiProbeVolume> volumes = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Material> materials = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<Control> controls = Collections.newSetFromMap(new IdentityHashMap<>());
    final java.util.List<com.jme3.post.WorldSettings> worlds = new java.util.ArrayList<>();
    private Node root;
    private boolean dirty = true;
    private final SceneChangeListener listener = (spatial, change) -> {
        if (change == SceneChangeListener.Change.ATTACHED
                || change == SceneChangeListener.Change.DETACHING
                || change == SceneChangeListener.Change.RENDERING) {
            dirty = true;
        }
    };

    void refresh(Node scene) {
        if (root != scene) {
            close();
            root = scene;
            root.addSceneChangeListener(listener);
        }
        if (!dirty) {
            return;
        }
        volumes.clear();
        materials.clear();
        controls.clear();
        worlds.clear();
        root.depthFirstTraversal(spatial -> {
            if (spatial instanceof GiProbeVolume) {
                volumes.add((GiProbeVolume) spatial);
            }
            if (spatial instanceof Geometry) {
                Material material = ((Geometry) spatial).getMaterial();
                if (material != null) {
                    materials.add(material);
                }
            }
            for (int i = 0; i < spatial.getNumControls(); i++) {
                Control control = spatial.getControl(i);
                controls.add(control);
                if (control instanceof com.jme3.post.WorldSettings) {
                    worlds.add((com.jme3.post.WorldSettings) control);
                }
            }
        });
        dirty = false;
    }

    @Override
    public void close() {
        if (root != null) {
            root.removeSceneChangeListener(listener);
            root = null;
        }
        volumes.clear();
        materials.clear();
        controls.clear();
        worlds.clear();
        dirty = true;
    }
}
