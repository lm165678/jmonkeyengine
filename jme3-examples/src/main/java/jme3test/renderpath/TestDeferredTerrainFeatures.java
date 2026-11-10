package jme3test.renderpath;

import com.jme3.light.LightProbe;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;

/** Multilayer terrain with fog, sunlight exposure and three probes (>16 textures). */
public class TestDeferredTerrainFeatures extends TestPBRTerrainRenderPath {
    @Override public void simpleInitApp() {
        super.simpleInitApp();
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                Material material = ((Geometry) spatial).getMaterial();
                if (material != null && material.getMaterialDef().getAssetName().endsWith("/PBRTerrain.j3md")) {
                    material.setFloat("StaticSunIntensity", .4f);
                    material.setBoolean("UseFog", true);
                    material.setColor("FogColor", new ColorRGBA(.2f, .3f, .4f, 1));
                    material.setFloat("ExpFog", .001f);
                }
            }
        });
        for (int i = 0; i < 2; i++) {
            LightProbe source = (LightProbe) assetManager.loadAsset("Scenes/LightProbes/quarry_Probe.j3o");
            LightProbe probe = new LightProbe();
            probe.setPrefilteredMap(source.getPrefilteredEnvMap());
            probe.setShCoeffs(source.getShCoeffs());
            probe.setNbMipMaps(source.getNbMipMaps());
            probe.setReady(true);
            probe.setPosition(new Vector3f(i == 0 ? -20 : 20, 20, 0));
            probe.getArea().setRadius(1000);
            rootNode.addLight(probe);
        }
        rootNode.addLight(new PointLight(cam.getLocation().clone(), ColorRGBA.White, 300));
    }

    public static void main(String[] args) { new TestDeferredTerrainFeatures().start(); }
}
