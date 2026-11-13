package com.jme3.scene.plugins.sdf;

import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class SurfaceSceneIndexTest {
    private Geometry template() {
        Geometry result = new Geometry("Baked", new Box(1, 1, 1));
        // Indexing only requires metadata; attribute upload is tested separately.
        result.setUserData(SurfaceBakeData.USER_DATA_KEY, SurfaceBakeData.prepare(result, "test"));
        return result;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void compareRigidRefreshAndRediscoveryInSameProcess() throws Exception {
        Node root = new Node();
        Geometry source = template();
        List<Geometry> moving = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            Geometry instance = source.clone(false);
            instance.setLocalTranslation((i % 100) * 10, 0, (i / 100) * 10);
            root.attachChild(instance);
            if (i % 100 == 0) {
                moving.add(instance);
            }
        }
        root.updateGeometricState();
        try (SurfaceSceneIndex fast = new SurfaceSceneIndex(root, 32);
                SurfaceSceneIndex reference = new SurfaceSceneIndex(root, 32)) {
            java.lang.reflect.Field movedField = SurfaceSceneIndex.class.getDeclaredField("moved");
            java.lang.reflect.Field dirtyField = SurfaceSceneIndex.class.getDeclaredField("dirty");
            movedField.setAccessible(true);
            dirtyField.setAccessible(true);
            long fastNanos = 0;
            long referenceNanos = 0;
            for (int frame = 0; frame < 600; frame++) {
                for (Geometry instance : moving) {
                    instance.setLocalTranslation(2 + (frame % 10) * .1f, 0, instance.getLocalTranslation().z);
                }
                root.updateGeometricState();
                // Force the prior rediscovery path on an otherwise identical index.
                java.util.Set<Spatial> changed = (java.util.Set<Spatial>) movedField.get(reference);
                ((java.util.Set<Spatial>) dirtyField.get(reference)).addAll(changed);
                changed.clear();
                for (int pass = 0; pass < 2; pass++) {
                    boolean runFast = (frame + pass) % 2 == 0;
                    SurfaceSceneIndex index = runFast ? fast : reference;
                    long start = System.nanoTime();
                    index.synchronize();
                    long elapsed = System.nanoTime() - start;
                    if (frame >= 200) {
                        if (runFast) {
                            fastNanos += elapsed;
                        } else {
                            referenceNanos += elapsed;
                        }
                    }
                }
                assertEquals(new HashSet<>(reference.query(Vector3f.ZERO, 100)),
                        new HashSet<>(fast.query(Vector3f.ZERO, 100)));
            }
            // Timings are observations, never flaky pass/fail thresholds.
            System.out.println("RIGID_INDEX_AB samples=400 fastMs=" + fastNanos / 400e6
                    + " rediscoveryMs=" + referenceNanos / 400e6);
        }
    }

    @Test
    public void rigidMovesPreserveEntriesAndRefreshExactQueriesAcrossCells() throws Exception {
        Node root = new Node();
        Geometry moving = template();
        moving.setLocalTranslation(10, 0, 0);
        root.attachChild(moving);
        root.updateGeometricState();
        try (SurfaceSceneIndex index = new SurfaceSceneIndex(root, 32)) {
            java.lang.reflect.Field entriesField = SurfaceSceneIndex.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            java.util.Map<?, ?> entries = (java.util.Map<?, ?>) entriesField.get(index);
            Object entry = entries.get(moving);
            assertTrue(index.query(Vector3f.ZERO, 5).isEmpty());
            moving.setLocalTranslation(3, 0, 0);
            root.updateGeometricState();
            index.synchronize();
            assertSame("Rigid moves must reuse the entry", entry, entries.get(moving));
            assertEquals(java.util.Collections.singletonList(moving), index.query(Vector3f.ZERO, 5));
            moving.setLocalTranslation(100, 0, 0);
            root.updateGeometricState();
            index.synchronize();
            assertSame(entry, entries.get(moving));
            assertTrue(index.query(Vector3f.ZERO, 5).isEmpty());
            assertEquals(java.util.Collections.singletonList(moving), index.query(new Vector3f(100, 0, 0), 5));
            moving.removeFromParent();
            root.updateGeometricState();
            index.synchronize();
            assertEquals(0, index.getInstanceCount());
        }
    }

    @Test
    public void detachedSubtreeMayBeEditedBeforeSynchronization() {
        Node root = new Node();
        Node branch = new Node();
        Geometry removed = template();
        Geometry moved = template();
        branch.attachChild(removed);
        branch.attachChild(moved);
        root.attachChild(branch);
        root.updateGeometricState();
        try (SurfaceSceneIndex index = new SurfaceSceneIndex(root, 8)) {
            branch.removeFromParent();
            // No callbacks from the detached branch reach root anymore.
            branch.detachAllChildren();
            root.attachChild(moved);
            root.updateGeometricState();
            index.synchronize();
            assertEquals(1, index.getInstanceCount());
            assertEquals(java.util.Collections.singletonList(moved), index.query(Vector3f.ZERO, 10));
        }
    }

    @Test
    public void staticTenThousandObjectsAndSingleMutationHaveBoundedWork() {
        Node root = new Node();
        Geometry template = template();
        Geometry changed = null;
        for (int i = 0; i < 10000; i++) {
            Geometry geometry = template.clone(false);
            geometry.setLocalTranslation((i % 100) * 20, 0, (i / 100) * 20);
            root.attachChild(geometry);
            if (i == 9999) {
                changed = geometry;
            }
        }
        root.updateGeometricState();
        try (SurfaceSceneIndex index = new SurfaceSceneIndex(root, 16)) {
            assertEquals(10000, index.getInstanceCount());
            index.synchronize();
            assertEquals(0, index.getVisitedNodeCount());
            assertEquals(1, index.query(Vector3f.ZERO, 5).size());
            assertTrue(index.getQueryCandidateCount() < 10);
            changed.setLocalTranslation(2, 0, 0);
            root.updateGeometricState();
            index.synchronize();
            assertTrue(index.getVisitedNodeCount() <= 2);
            assertEquals(2, index.query(Vector3f.ZERO, 5).size());
            changed.removeFromParent();
            root.updateGeometricState();
            index.synchronize();
            assertEquals(9999, index.getInstanceCount());
            assertEquals(1, index.query(Vector3f.ZERO, 5).size());
        }
    }

    @Test
    public void incrementalResultsMatchTraversalAfterRandomSceneMutations() {
        Node root = new Node();
        Node branch = new Node();
        root.attachChild(branch);
        Geometry template = template();
        List<Geometry> geometries = new ArrayList<>();
        Random random = new Random(7851);
        for (int i = 0; i < 300; i++) {
            Geometry geometry = template.clone(false);
            geometry.setLocalTranslation(random.nextFloat() * 100 - 50, 0, random.nextFloat() * 100 - 50);
            (i % 2 == 0 ? root : branch).attachChild(geometry);
            geometries.add(geometry);
        }
        root.updateGeometricState();
        try (SurfaceSceneIndex index = new SurfaceSceneIndex(root, 8)) {
            for (int round = 0; round < 60; round++) {
                Geometry geometry = geometries.get(random.nextInt(geometries.size()));
                geometry.setLocalTranslation(random.nextFloat() * 100 - 50, 0, random.nextFloat() * 100 - 50);
                if (round % 3 == 0) {
                    (round % 2 == 0 ? branch : root).attachChild(geometry);
                }
                if (round % 5 == 0) {
                    geometry.setUserData(SurfaceBakeData.USER_DATA_KEY, null);
                } else {
                    geometry.setUserData(SurfaceBakeData.USER_DATA_KEY, template.getUserData(SurfaceBakeData.USER_DATA_KEY));
                }
                if (round % 7 == 0) {
                    branch.setLocalTranslation(round, 0, 0);
                    branch.setLocalScale(1, 1, 1 + round * .01f);
                    branch.rotate(0, .1f, 0);
                }
                if (round == 20) {
                    branch.setUserData(SurfaceBakeData.USER_DATA_KEY, template.getUserData(SurfaceBakeData.USER_DATA_KEY));
                }
                if (round == 30) {
                    branch.setUserData(SurfaceBakeData.USER_DATA_KEY, null);
                }
                root.updateGeometricState();
                index.synchronize();
                Vector3f camera = new Vector3f(random.nextFloat() * 60 - 30, 0, random.nextFloat() * 60 - 30);
                List<Spatial> expected = new ArrayList<>();
                reference(root, camera, 15, expected);
                assertEquals(new HashSet<>(expected), new HashSet<>(index.query(camera, 15)));
            }
        }
    }

    @Test
    public void rootOwnershipInvalidationAndOversizedBoundsRemainCorrect() {
        Node root = new Node();
        Geometry geometry = template();
        geometry.setLocalScale(1000);
        root.attachChild(geometry);
        root.updateGeometricState();
        try (SurfaceSceneIndex index = new SurfaceSceneIndex(root, 8)) {
            assertEquals(1, index.query(new Vector3f(900, 0, 0), 1).size());
            SurfaceBakeData data = geometry.getUserData(SurfaceBakeData.USER_DATA_KEY);
            data.invalidate();
            assertTrue(index.query(Vector3f.ZERO, 1).isEmpty());
            geometry.setUserData(SurfaceBakeData.USER_DATA_KEY, template().getUserData(SurfaceBakeData.USER_DATA_KEY));
            root.updateGeometricState();
            index.synchronize();
            assertEquals(1, index.query(Vector3f.ZERO, 100000).size());
        }
    }

    private void reference(Spatial spatial, Vector3f camera, float radius, List<Spatial> result) {
        SurfaceBakeData data = spatial.getUserData(SurfaceBakeData.USER_DATA_KEY);
        if (data != null) {
            if (!data.isInvalidated() && SurfaceSceneIndex.distance(spatial, camera) <= radius) {
                result.add(spatial);
            }
        } else if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                reference(child, camera, radius, result);
            }
        }
    }
}
