package com.jme3.environment.gi;

import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.light.Light;
import com.jme3.light.LightProbe;
import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Storage isolation and static-bake contract; GPU sampling is tested separately. */
public class GiSkyIsolationTest {
    private GiProbeData data(float visibility, boolean compact) {
        GiProbeGrid grid = new GiProbeGrid(Vector3f.ZERO, Vector3f.UNIT_XYZ, 2, 2, 2);
        float[][] irradiance = new float[8][27];
        float[][] moments = new float[8][8];
        float[][] sky = visibility < 0 ? null : new float[8][27];
        for (int p = 0; p < 8; p++) {
            for (int c = 0; c < 27; c++) {
                irradiance[p][c] = (p + 1) * (c - 8) * 0.03f;
                if (sky != null) {
                    sky[p][c] = visibility * (c + 1);
                }
            }
            for (int c = 0; c < 8; c += 2) {
                moments[p][c] = 2;
                moments[p][c + 1] = 4.125f;
            }
        }
        return new GiProbeData(grid, 2, irradiance, moments, null, null, compact, 20, sky);
    }

    @Test
    public void skyPayloadCannotChangeOriginalTextures() {
        for (boolean compact : new boolean[]{false, true}) {
            GiProbeData legacy = data(-1, compact);
            GiProbeData closed = data(0, compact);
            GiProbeData open = data(1, compact);
            assertEquals(legacy.irradiance().getImage().getData(0), closed.irradiance().getImage().getData(0));
            assertEquals(legacy.irradiance().getImage().getData(0), open.irradiance().getImage().getData(0));
            assertEquals(legacy.moments().getImage().getData(0), open.moments().getImage().getData(0));
            assertEquals(legacy.moments().getImage().getData(0), closed.moments().getImage().getData(0));
            assertEquals(GiProbeData.LightingContent.TOTAL_IRRADIANCE, legacy.getLightingContent());
            assertEquals(GiProbeData.LightingContent.BOUNCED_IRRADIANCE_WITH_SKY_VISIBILITY, open.getLightingContent());
        }
    }

    @Test
    public void staticSkyChangesMarkStaleButKeepDataAndSurviveSave() throws Exception {
        Node root = new Node("scene");
        GiProbeVolume volume = new GiProbeVolume();
        root.attachChild(volume);
        SkyLight sky = new SkyLight();
        sky.setShCoeffs(new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()});
        sky.setReady(true);
        root.addLight(sky);
        root.updateGeometricState();
        GiProbeData baked = data(1, false);
        volume.publish(baked, GiSkyState.capture(root));
        assertEquals("Current", volume.getSkyBakeStatus());
        sky.setIntensity(2);
        assertEquals("Stale (rebake)", volume.getSkyBakeStatus());
        assertSame(baked, volume.getData());
        sky.setIntensity(1);
        assertEquals("Current", volume.getSkyBakeStatus());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BinaryExporter.getInstance().save(root, out);
        Node loaded = (Node) new BinaryImporter().load(new ByteArrayInputStream(out.toByteArray()));
        loaded.updateGeometricState();
        assertEquals("Current", ((GiProbeVolume) loaded.getChild(0)).getSkyBakeStatus());
        assertEquals(Light.Type.Sky, loaded.getLocalLightList().get(0).getType());
        assertFalse(LightProbe.class.isAssignableFrom(SkyLight.class));
        sky.invalidateCaptureRevision();
        assertEquals("Stale (rebake)", volume.getSkyBakeStatus());
        root.removeLight(sky);
        assertEquals("Stale (rebake)", volume.getSkyBakeStatus());
    }
}
