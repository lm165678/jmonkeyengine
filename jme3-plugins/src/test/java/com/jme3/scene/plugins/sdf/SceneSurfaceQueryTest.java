package com.jme3.scene.plugins.sdf;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class SceneSurfaceQueryTest {
    @Test
    public void missingAttributesDoNotTurnGeometricOcclusionIntoMiss() {
        Node root = new Node();
        Geometry box = new Geometry("occluder", new Box(1, 1, 1));
        root.attachChild(box);
        root.updateGeometricState();
        SceneSurfaceQuery query = new SceneSurfaceQuery();
        SceneSurfaceQuery.Hit hit = query.trace(root, new Vector3f(0, 0, 3), new Vector3f(0, 0, -2), 10);
        assertEquals(SceneSurfaceQuery.HitStatus.HIT, hit.status);
        assertSame(box, hit.geometry);
        assertEquals(2, hit.distance, 1e-6f);
        assertEquals(SceneSurfaceQuery.SurfaceStatus.MISSING_DATA, query.sample(hit, .01f).status);
        assertEquals(SceneSurfaceQuery.HitStatus.MISS,
                query.trace(root, new Vector3f(0, 0, 3), new Vector3f(0, 0, -1), 1).status);
        box.setLocalTranslation(5, 0, 0);
        root.updateGeometricState();
        assertEquals(SceneSurfaceQuery.HitStatus.MISS,
                query.trace(root, new Vector3f(0, 0, 3), new Vector3f(0, 0, -1), 10).status);
    }

    @Test
    public void emptyCardsAndInvalidatedDataHaveDifferentStatuses() {
        Geometry box = new Geometry("baked", new Box(1, 1, 1));
        SurfaceBakeData data = SurfaceBakeData.prepare(box, "test");
        float[][] cards = new float[6][8 * 8 * 13];
        for (float[] card : cards) {
            java.util.Arrays.fill(card, 8 * 8 * 12, card.length, 1);
        }
        data.complete(new SurfaceAttributeData(8, cards));
        data.attachTo(box);
        box.updateGeometricState();
        SceneSurfaceQuery query = new SceneSurfaceQuery();
        SceneSurfaceQuery.Hit hit = query.trace(box, new Vector3f(0, 0, 3), new Vector3f(0, 0, -1), 10);
        assertEquals(SceneSurfaceQuery.SurfaceStatus.UNCOVERED, query.sample(hit, .01f).status);
        data.invalidate();
        assertEquals(SceneSurfaceQuery.SurfaceStatus.STALE_DATA, query.sample(hit, .01f).status);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroDirectionIsRejected() {
        new SceneSurfaceQuery().trace(new Node(), Vector3f.ZERO, Vector3f.ZERO, 1);
    }
}
