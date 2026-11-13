package com.jme3.scene;

import com.jme3.bounding.BoundingBox;
import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class NodeUpdateIndexTest {
    private static final class CountingNode extends Node {
        int updates;

        @Override
        public void updateGeometricState() {
            updates++;
            super.updateGeometricState();
        }
    }

    @Test
    public void oneMovingChildDoesNotVisitItsStaticSiblings() {
        Node root = new Node();
        CountingNode[] children = new CountingNode[1000];
        for (int i = 0; i < children.length; i++) {
            children[i] = new CountingNode();
            children[i].attachChild(new Geometry("box", new Box(1, 1, 1)));
            root.attachChild(children[i]);
        }
        root.setIncrementalUpdatesEnabled(true);
        root.updateGeometricState();
        for (CountingNode child : children) {
            child.updates = 0;
        }
        children[500].move(20, 0, 0);
        root.updateGeometricState();
        for (int i = 0; i < children.length; i++) {
            assertEquals(i == 500 ? 1 : 0, children[i].updates);
        }
    }

    private Node scene(boolean indexed) {
        Node root = new Node();
        for (int i = 0; i < 128; i++) {
            Node model = new Node();
            model.attachChild(new Geometry("box", new Box(1, 1, 1)));
            model.setLocalTranslation(i * 3, i % 7, -i);
            root.attachChild(model);
        }
        root.setIncrementalUpdatesEnabled(indexed);
        return root;
    }

    private void compare(Node expected, Node indexed) {
        expected.updateGeometricState();
        indexed.updateGeometricState();
        BoundingBox a = (BoundingBox) expected.getWorldBound();
        BoundingBox b = (BoundingBox) indexed.getWorldBound();
        assertTrue(a.getCenter().distance(b.getCenter()) < .001f);
        assertTrue(a.getExtent(null).distance(b.getExtent(null)) < .001f);
    }

    @Test
    public void movementParentChangesAndReparentingMatchLinearUpdates() {
        Node expected = scene(false);
        Node indexed = scene(true);
        compare(expected, indexed);
        for (int i = 0; i < 40; i++) {
            expected.getChild(i * 3).move(i, -i, i * 2);
            indexed.getChild(i * 3).move(i, -i, i * 2);
            compare(expected, indexed);
        }
        expected.rotate(.2f, .4f, 0);
        indexed.rotate(.2f, .4f, 0);
        compare(expected, indexed);
        expected.detachChildAt(0);
        indexed.detachChildAt(0);
        compare(expected, indexed);
        expected.attachChild(new Geometry("new", new Box(50, 50, 50)));
        indexed.attachChild(new Geometry("new", new Box(50, 50, 50)));
        compare(expected, indexed);
        Node copy = indexed.clone(false);
        assertFalse(copy.isIncrementalUpdatesEnabled());
        compare(indexed, copy);
        indexed.setIncrementalUpdatesEnabled(false);
        compare(expected, indexed);
    }

    @Test
    public void lazyBoundsAndNonuniformParentScaleStayCorrect() {
        Node expected = scene(false);
        Node indexed = scene(true);
        compare(expected, indexed);
        expected.getChild(20).move(2000, 0, 0);
        indexed.getChild(20).move(2000, 0, 0);
        BoundingBox a = (BoundingBox) expected.getWorldBound();
        BoundingBox b = (BoundingBox) indexed.getWorldBound();
        assertTrue(a.getCenter().distance(b.getCenter()) < .001f);
        assertTrue(a.getExtent(null).distance(b.getExtent(null)) < .001f);
        expected.setLocalScale(2, .5f, 3);
        indexed.setLocalScale(2, .5f, 3);
        compare(expected, indexed);
        expected.detachAllChildren();
        indexed.detachAllChildren();
        compare(expected, indexed);
    }
}
