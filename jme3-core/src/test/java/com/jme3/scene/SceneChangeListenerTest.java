package com.jme3.scene;

import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneChangeListenerTest {
    @Test
    public void inheritedMobilityInvalidatesLightmapDraws() {
        Node root = new Node("root");
        Geometry child = new Geometry("receiver", new Box(1, 1, 1));
        root.attachChild(child);
        child.setResidentProbeLightmap(new com.jme3.texture.Texture2D());
        assertTrue(child.usesResidentProbeLightmap());
        int[] changes = {0};
        root.addSceneChangeListener((spatial, change) -> {
            if (change == SceneChangeListener.Change.RENDERING) {
                changes[0]++;
            }
        });
        root.setMobility(Mobility.Dynamic);
        assertFalse(child.usesResidentProbeLightmap());
        assertTrue(child.getWorldMatParamOverrides().isEmpty());
        root.setMobility(Mobility.Dynamic);
        assertEquals(1, changes[0]);
        child.setMobility(Mobility.Static);
        assertTrue(child.usesResidentProbeLightmap());
        child.setMobility(Mobility.Inherit);
        assertFalse(child.usesResidentProbeLightmap());
        root.setMobility(Mobility.Static);
        assertTrue(child.usesResidentProbeLightmap());
    }

    @Test
    public void mutationsBubbleAndClonesDoNotInheritObservers() {
        Node root = new Node("root");
        Geometry child = new Geometry("child", new Box(1, 1, 1));
        int[] counts = new int[SceneChangeListener.Change.values().length];
        SceneChangeListener listener = (spatial, change) -> {
            counts[change.ordinal()]++;
            if (change == SceneChangeListener.Change.DETACHING) {
                assertSame(root, spatial.getParent());
            }
        };
        root.addSceneChangeListener(listener);
        root.attachChild(child);
        child.setLocalTranslation(2, 0, 0);
        child.setUserData("value", 5);
        child.removeFromParent();
        assertEquals(1, counts[SceneChangeListener.Change.ATTACHED.ordinal()]);
        assertEquals(1, counts[SceneChangeListener.Change.DETACHING.ordinal()]);
        assertEquals(1, counts[SceneChangeListener.Change.USER_DATA.ordinal()]);
        assertTrue(counts[SceneChangeListener.Change.TRANSFORM.ordinal()] > 0);
        int before = counts[SceneChangeListener.Change.TRANSFORM.ordinal()];
        Node clone = root.clone(false);
        clone.move(1, 0, 0);
        assertEquals(before, counts[SceneChangeListener.Change.TRANSFORM.ordinal()]);
        root.removeSceneChangeListener(listener);
        root.move(1, 0, 0);
        assertEquals(before, counts[SceneChangeListener.Change.TRANSFORM.ordinal()]);
    }
}
