package com.jme3.scene;

import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneChangeListenerTest {
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
