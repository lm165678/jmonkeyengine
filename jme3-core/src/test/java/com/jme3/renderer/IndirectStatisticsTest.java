package com.jme3.renderer;

import com.jme3.scene.shape.Box;
import org.junit.Test;
import static org.junit.Assert.*;

public class IndirectStatisticsTest {
    @Test
    public void pendingIsUnknownAndSamplesUseLongCounts() {
        Statistics stats = new Statistics();
        stats.setEnabled(true);
        stats.onIndirectMeshDrawn();
        assertEquals(-1, stats.getTriangleCount());
        stats.publishGeometrySample(stats.getFrameSequence(), 3000000000L, 6000000000L);
        assertEquals(3000000000L, stats.getTriangleCount());
        int[] legacy = new int[stats.getLabels().length];
        stats.getData(legacy);
        assertEquals(Integer.MAX_VALUE, legacy[1]);
        stats.clearFrame();
        stats.onIndirectMeshDrawn();
        assertEquals(1, stats.getGeometrySampleAge());
        stats.invalidateGeometrySample();
        assertEquals(-1, stats.getTriangleCount());
    }

    @Test
    public void returningToDirectRenderingDoesNotReuseOldGpuCounts() {
        Statistics stats = new Statistics();
        stats.setEnabled(true);
        stats.onIndirectMeshDrawn();
        stats.publishGeometrySample(0, 9000000, 10000000);
        stats.clearFrame();
        stats.onMeshDrawn(new Box(1, 1, 1), 0, 2);
        assertEquals(24, stats.getTriangleCount());
        assertEquals(0, stats.getGeometrySampleAge());
    }
}
