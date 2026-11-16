package com.jme3.environment.gi;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Renderer;
import com.jme3.texture.Texture2D;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GiPagePoolTest {
    private GiProbeData data(boolean compact, int resolution) {
        float[][] sh = new float[8][27];
        float[][] moments = new float[8][resolution * resolution * 2];
        for (int i = 0; i < 8; i++) {
            sh[i][0] = 1;
            Arrays.fill(moments[i], 1);
        }
        return new GiProbeData(new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2),
                resolution, sh, moments, null, null, compact, 1);
    }

    @Test
    public void incrementallyUploadsAndPublishesWithoutMovingExistingPages() {
        List<int[]> calls = new ArrayList<>();
        List<Texture2D> targets = new ArrayList<>();
        Renderer renderer = (Renderer) Proxy.newProxyInstance(Renderer.class.getClassLoader(),
                new Class[]{Renderer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("modifyTexture")) {
                        targets.add((Texture2D) args[0]);
                        calls.add(new int[]{(int) args[3], (int) args[6], (int) args[7]});
                    }
                    return null;
                });
        try (GiPagePool pool = new GiPagePool(renderer, 64, 150000)) {
            assertTrue(pool.offer("a", data(true, 2), Vector3f.ZERO, 1));
            pool.upload(432, Long.MAX_VALUE);
            assertEquals(0, pool.publishedCount());
            assertEquals(432, pool.uploadedBytes());
            pool.upload(100000, Long.MAX_VALUE);
            assertEquals(1, pool.publishedCount());
            int initialCalls = calls.size();
            Texture2D sh = targets.get(0);
            float first = sh.getImage().getData(0).getFloat(0);
            assertTrue(pool.offer("b", data(false, 4), Vector3f.UNIT_X, 1));
            pool.upload(100000, Long.MAX_VALUE);
            assertEquals(2, pool.publishedCount());
            assertEquals(first, sh.getImage().getData(0).getFloat(0), 0);
            for (int i = initialCalls; i < calls.size(); i++) {
                if (targets.get(i) == sh) {
                    assertTrue(calls.get(i)[0] >= 4);
                }
            }
            pool.retain(Collections.singleton("b"));
            assertEquals(1, pool.publishedCount());
            assertTrue(pool.offer("c", data(true, 2), Vector3f.ZERO, 1));
            pool.upload(100000, Long.MAX_VALUE);
            assertEquals(2, pool.publishedCount());
            long before = pool.uploadedBytes();
            pool.upload(100000, Long.MAX_VALUE);
            assertEquals(before, pool.uploadedBytes());
        }
    }

    @Test
    public void externalReferenceRoundTripDoesNotEmbedPayload() throws Exception {
        GiProbeVolume volume = new GiProbeVolume();
        volume.configure(Vector3f.UNIT_XYZ, 2, 2, 2);
        volume.updateGeometricState();
        GiProbeData data = data(true, 2);
        volume.publish(data);
        volume.setIntensity(2.5f);
        volume.setFadeDistance(0.3f);
        assertSame(data, volume.getData());
        assertTrue(volume.useStreamAsset("GI/revision.giprobe", data));
        assertNull(volume.getData());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        com.jme3.export.binary.BinaryExporter.getInstance().save(volume, out);
        GiProbeVolume restored = (GiProbeVolume) new com.jme3.export.binary.BinaryImporter()
                .load(new java.io.ByteArrayInputStream(out.toByteArray()));
        restored.updateGeometricState();
        assertEquals("GI/revision.giprobe", restored.getStreamAsset());
        assertEquals(2.5f, restored.getIntensity(), 0);
        assertEquals(0.3f, restored.getFadeDistance(), 0);
        assertEquals(data.getTextureBytes(), restored.getStreamBytes());
        assertNull(restored.getData());
        restored.setLocalTranslation(1, 0, 0);
        restored.updateGeometricState();
        assertNull(restored.getStreamAsset());
        restored.publish(data);
        assertNull(restored.getStreamAsset());
    }
}
