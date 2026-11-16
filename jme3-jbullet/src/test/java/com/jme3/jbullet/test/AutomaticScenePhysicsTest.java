package com.jme3.jbullet.test;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AutomaticScenePhysicsTest {
    private SimpleApplication app(AppState... states) {
        return new SimpleApplication(states) {
            @Override
            public void simpleInitApp() {
            }
        };
    }

    private void frames(SimpleApplication app) {
        app.getRootNode().updateGeometricState();
        for (int i = 0; i < 3; i++) {
            app.getStateManager().update(0);
        }
    }

    private Node scene(boolean enabled) {
        Node node = new Node("body");
        RigidBodyControl control = new RigidBodyControl(new BoxCollisionShape(Vector3f.UNIT_XYZ), 1);
        control.setEnabled(enabled);
        node.addControl(control);
        return node;
    }

    @Test
    public void deserializedControlActivatesOnlyAfterAttachmentAndReleasesOnRemoval() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(scene(true), output);
        Node loaded = (Node) new BinaryImporter().load(new ByteArrayInputStream(output.toByteArray()));
        RigidBodyControl control = loaded.getControl(RigidBodyControl.class);
        SimpleApplication app = app();
        try {
            frames(app);
            assertNull(app.getStateManager().getState(BulletAppState.class));
            assertNull(control.getPhysicsSpace());
            app.getRootNode().attachChild(loaded);
            frames(app);
            BulletAppState state = app.getStateManager().getState(BulletAppState.class);
            assertNotNull(state);
            assertSame(state.getPhysicsSpace(), control.getPhysicsSpace());
            assertEquals(1, state.getPhysicsSpace().getRigidBodyList().size());
            frames(app);
            assertSame(state, app.getStateManager().getState(BulletAppState.class));
            loaded.removeFromParent();
            frames(app);
            assertNull(control.getPhysicsSpace());
            assertNull(app.getStateManager().getState(BulletAppState.class));
        } finally {
            app.getSceneServices().setEnabled(false);
            app.getStateManager().cleanup();
        }
    }

    @Test
    public void reusesApplicationStateAndPreservesDisabledControls() {
        BulletAppState manual = new BulletAppState();
        SimpleApplication app = app(manual);
        Node node = scene(false);
        RigidBodyControl control = node.getControl(RigidBodyControl.class);
        app.getRootNode().attachChild(node);
        try {
            frames(app);
            assertSame(manual, app.getStateManager().getState(BulletAppState.class));
            assertSame(manual.getPhysicsSpace(), control.getPhysicsSpace());
            assertFalse(control.isEnabled());
            assertEquals(0, manual.getPhysicsSpace().getRigidBodyList().size());
            app.getSceneServices().setEnabled(false);
            assertNull(control.getPhysicsSpace());
            assertSame(manual, app.getStateManager().getState(BulletAppState.class));
            app.getSceneServices().setEnabled(true);
            frames(app);
            assertSame(manual.getPhysicsSpace(), control.getPhysicsSpace());
        } finally {
            app.getSceneServices().setEnabled(false);
            app.getStateManager().cleanup();
        }
    }

    @Test
    public void neverRemovesPreexistingManualRegistration() {
        BulletAppState manual = new BulletAppState();
        SimpleApplication app = app(manual);
        Node node = scene(true);
        RigidBodyControl control = node.getControl(RigidBodyControl.class);
        control.setPhysicsSpace(manual.getPhysicsSpace());
        app.getRootNode().attachChild(node);
        try {
            frames(app);
            node.removeFromParent();
            frames(app);
            assertSame(manual.getPhysicsSpace(), control.getPhysicsSpace());
            app.getSceneServices().setEnabled(false);
            assertSame(manual.getPhysicsSpace(), control.getPhysicsSpace());
        } finally {
            control.setPhysicsSpace(null);
            app.getSceneServices().setEnabled(false);
            app.getStateManager().cleanup();
        }
    }
}
