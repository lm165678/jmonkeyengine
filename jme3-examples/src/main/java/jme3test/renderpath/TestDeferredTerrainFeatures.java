package jme3test.renderpath;

import com.jme3.light.LightProbe;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;

/**
 Multilayer terrain with fog, sunlight exposure and three probes (>16 textures).
 * <p>Pipeline/FrameGraph usage: run main() from jme3-examples with OpenGL 4.3
 * for GPU culling. Existing scene controls remain available; helper-based scenes
 * use SPACE (terrain scenes: K). Configuration runs on the render thread.</p>
 * <pre>{@code
 * renderManager.setDeferredTileSize(16);
 * useRenderPath(RenderPath.TiledDeferredGPU);
 * // Switch back using the original Forward renderer:
 * useRenderPath(RenderPath.Forward);
 * }</pre>
 * <p>RenderPathHelper.apply shows the full public API: install DeferredPipeline
 * with the shared model registry, select LightCullingMode and tile size, or
 * install ForwardPipeline. DeferredPipeline owns its FrameGraph and GBuffer pool.
 * Query its active path and fallback reason after rendering; a GPU request may
 * fall back to CPU or Forward on unsupported contexts/materials.</p>
 * <p>Load materials from .j3md. Custom definitions declare DeferredLightingShader
 * and provide default Forward/GBuffer techniques; application code does not load
 * or register a .frag. See Materials/Tiled/CustomLambert.j3md for a complete asset.</p>
 * <p>Automated check: gradlew :jme3-examples:renderPathSmoke
 * -PrenderPathExample=TestDeferredTerrainFeatures. Checks CPU/GPU pixels and Forward round trips;
 * renderPipelineSuite additionally checks direct Pipeline installation.</p>
 */
public class TestDeferredTerrainFeatures extends TestPBRTerrainRenderPath {
    @Override
    public void simpleInitApp() {
        super.simpleInitApp();
        rootNode.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                Material material = ((Geometry) spatial).getMaterial();
                if (material != null
                    && material.getMaterialDef().getAssetName().endsWith("/PBRTerrain.j3md")) {
                    material.setFloat("StaticSunIntensity", .4f);
                    material.setBoolean("UseFog", true);
                    material.setColor("FogColor", new ColorRGBA(.2f, .3f, .4f, 1));
                    material.setFloat("ExpFog", .001f);
                }
            }
        });
        for (int i = 0; i < 2; i++) {
            LightProbe source =
                (LightProbe) assetManager.loadAsset("Scenes/LightProbes/quarry_Probe.j3o");
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

    public static void main(String[] args) {
        new TestDeferredTerrainFeatures().start();
    }
}
