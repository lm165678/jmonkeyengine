package com.jme3.environment.gi;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Box;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiWorldTest {
    private GiProbeData data(boolean compact) {
        float[][] sh = new float[8][27];
        float[][] moments = new float[8][8];
        Vector3f[] offsets = new Vector3f[8];
        boolean[] valid = new boolean[8];
        for (int p = 0; p < 8; p++) {
            Arrays.fill(sh[p], 123456f);
            for (int c = 0; c < 8; c += 2) {
                moments[p][c] = 100000;
                moments[p][c + 1] = 1e10f;
            }
            offsets[p] = new Vector3f(0.2f, -0.1f, 0);
            valid[p] = p != 3;
        }
        return new GiProbeData(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2),
                2, sh, moments, offsets, valid, compact, 100000);
    }

    @Test
    public void normalizedHalfStoragePreservesLargeDistancesAndValidity() throws Exception {
        GiProbeData compact = data(true);
        assertEquals(data(false).getTextureBytes() / 2, compact.getTextureBytes());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        compact.saveCompressed(bytes);
        GiProbeData loaded = GiProbeData.loadCompressed(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(100000, loaded.distanceScale(), 0);
        assertEquals(123456, loaded.irradianceScale(), 0);
        assertEquals(1f, GiProbeData.component(loaded.moments().getImage(), 0), 0f);
        assertEquals(1f, GiProbeData.component(loaded.moments().getImage(), 1), 0f);
        assertEquals(1f, GiProbeData.component(loaded.irradiance().getImage(), 3), 0f);
        assertEquals(0f, GiProbeData.component(loaded.irradiance().getImage(), 3 * 9 * 4 + 3), 0f);
        assertEquals(0.2f, GiProbeData.component(loaded.irradiance().getImage(), 7), 0.001f);
    }

    @Test
    public void placementRejectsInteriorAndMovesNearSurfaceWithinCell() {
        Geometry box = new Geometry("closed", new Box(1, 1, 1));
        box.updateGeometricState();
        Vector3f result = new Vector3f();
        assertFalse(GiProbePlacement.place(box, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.1f, result));
        assertTrue(GiProbePlacement.place(box, new Vector3f(1.02f, 0, 0), Vector3f.UNIT_XYZ, 0.1f, result));
        assertTrue(result.x > 1.1f);
        assertTrue(result.distance(new Vector3f(1.02f, 0, 0)) < 0.45f);
    }

    @Test
    public void streamedLoadsRebaseEvictAndRestoreWithoutBlockingIo() throws Exception {
        ExecutorService io = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        GiProbeData fixture = data(true);
        try (GiProbeStreamer stream = new GiProbeStreamer(io, 1024 * 1024, 1024, 10)) {
            stream.register(new GiProbeStreamer.Chunk("a", 1e12, 0, 0, Vector3f.UNIT_XYZ,
                    fixture.getTextureBytes(), () -> {
                        release.await();
                        return fixture;
                    }));
            stream.addMaterial(material);
            stream.update(Vector3f.ZERO, 1e12, 0, 0);
            assertEquals(1, stream.getPendingCount());
            assertEquals(0, stream.getResidentCount());
            assertNull(material.getParam("GiIrradiance"));
            release.countDown();
            for (int attempt = 0; attempt < 100 && stream.getResidentCount() == 0; attempt++) {
                Thread.sleep(5);
                stream.update(Vector3f.ZERO, 1e12, 0, 0);
            }
            assertEquals(1, stream.getResidentCount());
            assertEquals(1, material.getParam("GiVolumeCount").getValue());
            stream.update(new Vector3f(-0.25f, 0, 0), 1e12 + 0.25, 0, 0);
            assertEquals(1, stream.getResidentCount());
            stream.update(new Vector3f(1000, 0, 0), 1e12, 0, 0);
            assertEquals(0, stream.getResidentCount());
            assertNull(material.getParam("GiIrradiance"));
            assertTrue(stream.getFailures().isEmpty());
        } finally {
            release.countDown();
            io.shutdownNow();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void atlasBudgetCheckedBeforeAllocation() {
        new GiProbeSet(Arrays.asList(new GiProbeSet.Entry(data(true), Vector3f.ZERO, 1)), 1024, 1);
    }

    @Test
    public void skyAndHiddenShellsDoNotInvalidateOutdoorProbes() {
        Geometry shell = new Geometry("sky", new Box(100, 100, 100));
        shell.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Sky);
        shell.updateGeometricState();
        assertTrue(GiProbePlacement.place(shell, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.05f, new Vector3f()));
        shell.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Opaque);
        shell.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        assertTrue(GiProbePlacement.place(shell, Vector3f.ZERO, Vector3f.UNIT_XYZ, 0.05f, new Vector3f()));
    }

    @Test
    public void rejectedAtlasCandidateDoesNotCausePerFrameRebuild() throws Exception {
        ExecutorService io = Executors.newSingleThreadExecutor();
        GiProbeData small = data(true);
        GiProbeData large = new GiProbeData(small.getGrid(), 8, new float[8][27], new float[8][128]);
        Material material = new Material(new DesktopAssetManager(true), "Common/MatDefs/Misc/Unshaded.j3md");
        try (GiProbeStreamer stream = new GiProbeStreamer(io, 1024 * 1024, 27, 10)) {
            stream.register(new GiProbeStreamer.Chunk("small", 0, 0, 0, Vector3f.UNIT_XYZ,
                    small.getTextureBytes(), () -> small));
            stream.register(new GiProbeStreamer.Chunk("large", 1, 0, 0, Vector3f.UNIT_XYZ,
                    large.getTextureBytes(), () -> large));
            stream.addMaterial(material);
            for (int attempt = 0; attempt < 100 && stream.getResidentCount() < 2; attempt++) {
                stream.update(Vector3f.ZERO, 0, 0, 0);
                Thread.sleep(5);
            }
            assertEquals(2, stream.getResidentCount());
            assertEquals(1, material.getParam("GiVolumeCount").getValue());
            Object atlas = material.getParam("GiIrradiance").getValue();
            for (int frame = 0; frame < 20; frame++) {
                stream.update(Vector3f.ZERO, 0, 0, 0);
                assertSame(atlas, material.getParam("GiIrradiance").getValue());
            }
        } finally {
            io.shutdownNow();
        }
    }
}
