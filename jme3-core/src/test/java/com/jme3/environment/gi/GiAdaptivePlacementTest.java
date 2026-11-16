package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Quad;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiAdaptivePlacementTest {
    @Test
    public void solidClassificationDoesNotDependOnTriangleWinding() {
        Box mesh = new Box(1, 1, 1);
        int[] reversed = new int[mesh.getTriangleCount() * 3];
        int[] triangle = new int[3];
        for (int i = 0; i < mesh.getTriangleCount(); i++) {
            mesh.getTriangle(i, triangle);
            reversed[i * 3] = triangle[2];
            reversed[i * 3 + 1] = triangle[1];
            reversed[i * 3 + 2] = triangle[0];
        }
        mesh.clearBuffer(com.jme3.scene.VertexBuffer.Type.Index);
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3, reversed);
        Geometry box = new Geometry("inverted", mesh);
        box.updateGeometricState();
        GiProbeSolidClassifier classifier = new GiProbeSolidClassifier(box);
        assertTrue(classifier.inside(Vector3f.ZERO));
        assertFalse(classifier.inside(new Vector3f(2, 0, 0)));
        Vector3f result = new Vector3f();
        assertTrue(GiProbePlacement.place(box, new Vector3f(0.9f, 0, 0), Vector3f.UNIT_XYZ, 0.1f, result));
        assertTrue(result.x > 1.1f);
    }

    @Test
    public void exactDistanceIncludesEdgesAndRespectsNonuniformScale() {
        Geometry box = new Geometry("scaled", new Box(1, 1, 1));
        box.setLocalScale(2, 1, 0.5f);
        box.updateGeometricState();
        Vector3f closest = new Vector3f();
        float distance = GiProbePlacement.nearestSurface(box,
                new Vector3f(2.03f, 1.04f, 0), 0.2f, closest, new Vector3f());
        assertEquals(0.05f, distance, 0.00001f);
        assertEquals(new Vector3f(2, 1, 0), closest);
    }

    @Test
    public void relocationRechecksBothSidesOfTooNarrowGap() {
        Node scene = new Node();
        for (float x : new float[]{-0.06f, 0.06f}) {
            Geometry wall = new Geometry("wall", new Box(0.01f, 1, 1));
            wall.setLocalTranslation(x, 0, 0);
            scene.attachChild(wall);
        }
        scene.updateGeometricState();
        assertFalse(GiProbePlacement.place(scene, Vector3f.ZERO,
                Vector3f.UNIT_XYZ, 0.1f, new Vector3f()));
    }

    @Test
    public void recursiveLayoutProducesMultipleDensitiesWithinTotalBudget() {
        Node scene = new Node();
        Geometry obstacle = new Geometry("corner", new Box(0.1f, 0.1f, 0.1f));
        obstacle.setLocalTranslation(1, 1, 1);
        scene.attachChild(obstacle);
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(8, 8, 8), 5, 5, 5);
        scene.attachChild(volume);
        scene.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxDepth = 2;
        settings.maxRefinements = 3;
        List<GiProbeVolume> bricks = GiAdaptiveProbeLayout.plan(scene, volume, settings);
        assertTrue(bricks.size() <= 3);
        assertTrue(bricks.stream().anyMatch(b -> Integer.valueOf(2).equals(b.getUserData("jme.gi.adaptive.level"))));
        float minimum = Float.POSITIVE_INFINITY;
        for (GiProbeVolume brick : bricks) {
            minimum = Math.min(minimum, brick.getGrid().getSpacing().x);
        }
        assertTrue(minimum < 0.75f);
    }

    @Test
    public void relocatesInteriorProbeThroughNearestExitAndKeepsCellLimit() {
        Geometry box = new Geometry("solid", new Box(1, 1, 1));
        box.updateGeometricState();
        Vector3f moved = new Vector3f();
        assertTrue(GiProbePlacement.place(box, new Vector3f(0.9f, 0, 0), Vector3f.UNIT_XYZ, 0.1f, moved));
        assertTrue(moved.x > 1.1f);
        assertFalse(GiProbePlacement.place(box, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.1f, moved));
    }

    @Test
    public void isolatedBackFacingPatchIsNotAClosedSolid() {
        Geometry patch = new Geometry("open patch", new Quad(0.2f, 0.2f));
        patch.setLocalTranslation(-0.1f, -0.1f, 0.2f);
        patch.updateGeometricState();
        Vector3f moved = new Vector3f();
        assertTrue(GiProbePlacement.place(patch, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.05f, moved));
        assertEquals(Vector3f.ZERO, moved);
    }

    @Test
    public void ignoresHiddenAncestorsAndTransparentShells() {
        Node hidden = new Node();
        Geometry box = new Geometry("helper", new Box(1, 1, 1));
        hidden.attachChild(box);
        hidden.setCullHint(Spatial.CullHint.Always);
        box.setCullHint(Spatial.CullHint.Never);
        hidden.updateGeometricState();
        assertTrue(GiProbePlacement.place(hidden, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.1f, new Vector3f()));
        hidden.setCullHint(Spatial.CullHint.Inherit);
        box.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
        assertTrue(GiProbePlacement.place(hidden, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.1f, new Vector3f()));
    }

    @Test
    public void refinesOnlyOccupiedCellsWithinBudgetAndSourceCoverage() {
        Node scene = new Node();
        Geometry obstacle = new Geometry("small obstacle", new Box(0.1f, 0.1f, 0.1f));
        obstacle.setLocalTranslation(1, 1, 1);
        scene.attachChild(obstacle);
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(8, 8, 8), 5, 5, 5);
        scene.attachChild(volume);
        scene.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxDepth = 1;
        List<GiProbeVolume> refinements = GiAdaptiveProbeLayout.plan(scene, volume, settings);
        assertEquals("Only one occupied cell, not all 64 cells", 1, refinements.size());
        GiProbeVolume refined = refinements.get(0);
        assertNull(refined.getParent());
        assertEquals(new Vector3f(5, 5, 5), refined.getProbeCounts());
        assertTrue(refined.getCoverage().x < volume.getCoverage().x);
        assertTrue(refined.getGrid().getSpacing().x < volume.getGrid().getSpacing().x);
        assertEquals(0, volume.getQuantity());
        settings.maxRefinements = 0;
        assertTrue(GiAdaptiveProbeLayout.plan(scene, volume, settings).isEmpty());
        obstacle.removeFromParent();
        scene.updateGeometricState();
        settings.maxRefinements = 4;
        assertTrue(GiAdaptiveProbeLayout.plan(scene, volume, settings).isEmpty());
    }

    @Test
    public void limitedBudgetDoesNotStarveSmallSeparatedObstacle() {
        Node scene = new Node();
        Geometry large = new Geometry("large", new Box(1.8f, 1.8f, 1.8f));
        large.setLocalTranslation(3, 3, 3);
        Geometry small = new Geometry("small", new Box(0.15f, 0.15f, 0.15f));
        small.setLocalTranslation(9, 1, 1);
        scene.attachChild(large);
        scene.attachChild(small);
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(12, 8, 8), 7, 5, 5);
        scene.attachChild(volume);
        scene.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxDepth = 1;
        settings.maxRefinements = 2;
        List<GiProbeVolume> bricks = GiAdaptiveProbeLayout.plan(scene, volume, settings);
        assertEquals(2, bricks.size());
        assertTrue("Small obstacle must receive coverage despite tiny area",
                bricks.stream().anyMatch(brick -> contains(brick, small.getWorldTranslation())));
        assertTrue("The second slot must cover the large obstacle region",
                bricks.stream().anyMatch(brick -> brick.getLocalTranslation().x < 6));
    }

    @Test
    public void mergedMeshKeepsSmallObjectAsIndependentCoverageTarget() {
        Box big = new Box(1.8f, 1.8f, 1.8f);
        Box small = new Box(0.15f, 0.15f, 0.15f);
        java.nio.FloatBuffer positions = com.jme3.util.BufferUtils.createFloatBuffer(24 * 9);
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        for (int object = 0; object < 2; object++) {
            Box box = object == 0 ? big : small;
            Vector3f offset = object == 0 ? new Vector3f(3, 3, 3) : new Vector3f(9, 1, 1);
            for (int triangle = 0; triangle < box.getTriangleCount(); triangle++) {
                box.getTriangle(triangle, a, b, c);
                for (Vector3f vertex : new Vector3f[]{a, b, c}) {
                    positions.put(vertex.x + offset.x).put(vertex.y + offset.y).put(vertex.z + offset.z);
                }
            }
        }
        positions.flip();
        com.jme3.scene.Mesh mesh = new com.jme3.scene.Mesh();
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, positions);
        mesh.updateBound();
        Node scene = new Node();
        scene.attachChild(new Geometry("merged import", mesh));
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(12, 8, 8), 7, 5, 5);
        scene.attachChild(volume);
        scene.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxDepth = 1;
        settings.maxRefinements = 2;
        List<GiProbeVolume> bricks = GiAdaptiveProbeLayout.plan(scene, volume, settings);
        assertEquals(2, bricks.size());
        assertTrue(bricks.stream().anyMatch(brick -> contains(brick, new Vector3f(9, 1, 1))));
        assertTrue(bricks.stream().anyMatch(brick -> brick.getLocalTranslation().x < 6));
    }

    @Test
    public void surfaceTargetsRejectSolidSideAndSupportCannotCrossWall() {
        Geometry wall = new Geometry("wall", new Box(0.1f, 2, 2));
        wall.updateGeometricState();
        GiSurfaceCoverage check = new GiSurfaceCoverage(wall,
                GiProbePlacement.validator(wall), 0.02f, 0.02f);
        assertNull(check.target(new Vector3f(0.1f, 0, 0), new Vector3f(-1, 0, 0),
                wall, Vector3f.UNIT_XYZ));
        GiSurfaceCoverage.Target target = check.target(new Vector3f(0.1f, 0, 0),
                Vector3f.UNIT_X, wall, Vector3f.UNIT_XYZ);
        assertNotNull(target);
        Vector3f[] probes = new Vector3f[125];
        java.util.Arrays.fill(probes, new Vector3f(-1, 0, 0));
        assertFalse(check.supports(target, new Vector3f(-2, -2, -2), Vector3f.UNIT_XYZ, probes));
        java.util.Arrays.fill(probes, new Vector3f(1, 0, 0));
        assertTrue(check.supports(target, new Vector3f(-2, -2, -2), Vector3f.UNIT_XYZ, probes));
    }

    @Test
    public void reportsUncoveredSurfacesAndAdditionalBudgetImprovesCoverage() {
        Node scene = new Node();
        for (int x : new int[]{1, 9}) {
            Geometry box = new Geometry("separate", new Box(0.15f, 0.15f, 0.15f));
            box.setLocalTranslation(x, 1, 1);
            scene.attachChild(box);
        }
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(new Vector3f(12, 4, 4), 7, 3, 3);
        scene.attachChild(volume);
        scene.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxDepth = 1;
        settings.maxRefinements = 1;
        GiAdaptiveProbeLayout.Plan one = GiAdaptiveProbeLayout.planDetailed(scene, volume, settings);
        assertTrue(one.getCoveredTargets() > 0);
        assertTrue(one.getCoveredTargets() < one.getSurfaceTargets());
        settings.maxRefinements = 2;
        GiAdaptiveProbeLayout.Plan two = GiAdaptiveProbeLayout.planDetailed(scene, volume, settings);
        assertEquals(one.getSurfaceTargets(), two.getSurfaceTargets());
        assertTrue(two.getCoveredTargets() > one.getCoveredTargets());
    }

    private static boolean contains(GiProbeVolume brick, Vector3f point) {
        Vector3f low = brick.getLocalTranslation();
        Vector3f high = low.add(brick.getCoverage());
        return point.x > low.x && point.x < high.x && point.y > low.y && point.y < high.y
                && point.z > low.z && point.z < high.z;
    }

    @Test(expected = IllegalArgumentException.class)
    public void failsExplicitlyWhenPlanningBudgetIsExceeded() {
        GiProbeVolume volume = new GiProbeVolume();
        volume.updateGeometricState();
        GiAdaptiveProbeLayout.Settings settings = new GiAdaptiveProbeLayout.Settings();
        settings.maxCells = 1;
        GiAdaptiveProbeLayout.plan(new Node(), volume, settings);
    }
}
