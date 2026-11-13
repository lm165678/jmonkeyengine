package jme3test.renderpath;

import com.jme3.renderer.RenderPath;

import com.jme3.app.SimpleApplication;
import com.jme3.light.PointLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.ToneMapFilter;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.instancing.InstancedNode;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Sphere;

/**
 * <a
 href="https://en.wikipedia.org/wiki/Deferred_shading/">https://en.wikipedia.org/wiki/Deferred_shading/</a>
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
 * install OpaqueForwardPipeline. DeferredPipeline appends passes to the viewport FrameGraph and its GBuffer pool.
 * Query its active path and fallback reason after rendering; a GPU request may
 * fall back to CPU or Forward on unsupported contexts/materials.</p>
 * <p>Load materials from .j3md. Custom definitions declare DeferredLightingShader
 * and provide default Forward/GBuffer techniques; application code does not load
 * or register a .frag. See Materials/Tiled/CustomLambert.j3md for a complete asset.</p>
 * <p>Automated check: gradlew :jme3-examples:renderPathSmoke
 * -PrenderPathExample=TestDeferredShading. Checks CPU/GPU pixels and Forward round trips;
 * renderPipelineSuite additionally checks direct Pipeline installation.</p>
 * @author JohnKkk

 */
public class TestDeferredShading extends RenderPathExample {
    private Material material;
    @Override
    public void simpleInitApp() {
        // Test Forward↓
        // Light lists grow automatically; GPU tile overflow does not drop lights.// Pre-allocate a
        // maximum value for light sources to ensure the maximum number of light sources in the
        // scene does not exceed this value.
        useRenderPath(RenderPath.Deferred);
        renderManager.setSinglePassLightBatchSize(200);
        Quad quad = new Quad(15, 15);
        Geometry geo = new Geometry("Floor", quad);
        material = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        material.setFloat("Shininess", 25);
        material.setColor("Ambient", ColorRGBA.White);
        material.setColor("Diffuse", ColorRGBA.White);
        material.setColor("Specular", ColorRGBA.White);
        material.setBoolean("UseMaterialColors", true);
        geo.setMaterial(material);
        geo.rotate((float) Math.toRadians(-90), 0, 0);
        geo.setLocalTranslation(-7, 0, -7);
        rootNode.attachChild(geo);

        Sphere sphere = new Sphere(15, 15, 0.1f);
        Geometry sp = new Geometry("sp", sphere);
        sp.setMaterial(material.clone());
        sp.getMaterial().setBoolean("UseInstancing", true);
        ColorRGBA colors[] = new ColorRGBA[] {
            ColorRGBA.White,
            ColorRGBA.Red,
            ColorRGBA.Blue,
            ColorRGBA.Green,
            ColorRGBA.Yellow,
            ColorRGBA.Orange,
            ColorRGBA.Brown,
        };

        InstancedNode instancedNode = new InstancedNode("sp");
        for (int i = 0; i < 1000; i++) {
            PointLight pl = new PointLight();
            pl.setColor(colors[i % colors.length]);
            pl.setPosition(new Vector3f(RenderPathHelper.randomFloat(-5.0f, 5.0f), 0.1f,
                RenderPathHelper.randomFloat(-20.0f, -10.0f)));
            //            pl.setPosition(new Vector3f(0, 1, 0));
            pl.setRadius(1.0f);
            rootNode.addLight(pl);
            Geometry g = sp.clone(false);
            //            g.getMaterial().setColor("Ambient",  ColorRGBA.Gray);
            //            g.getMaterial().setColor("Diffuse", colors[i % colors.length]);
            g.setLocalTranslation(pl.getPosition());
            instancedNode.attachChild(g);
        }
        instancedNode.instance();
        rootNode.attachChild(instancedNode);

        // AmbientLight ambientLight = new AmbientLight(new ColorRGBA(0.15f, 0.15f, 0.15f, 1.0f));
        // rootNode.addLight(ambientLight);
        // DirectionalLight sun = new DirectionalLight();
        // sun.setDirection(new Vector3f(-0.5f, -0.5f, -0.5f).normalizeLocal());
        // sun.setColor(ColorRGBA.Gray);
        // rootNode.addLight(sun);

        cam.setLocation(new Vector3f(0, 2, 0));
        cam.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        flyCam.setMoveSpeed(10.0f);

        FilterPostProcessor fpp = new FilterPostProcessor(assetManager);
        int numSamples = context.getSettings().getSamples();
        if (numSamples > 0) {
            fpp.setNumSamples(numSamples);
        }

        BloomFilter bloom = new BloomFilter();
        bloom.setDownSamplingFactor(1);
        bloom.setBlurScale(1.1f);
        bloom.setExposurePower(1.30f);
        bloom.setExposureCutOff(0.3f);
        bloom.setBloomIntensity(1.15f);
        fpp.addFilter(bloom);

        fpp.addFilter(new ToneMapFilter(Vector3f.UNIT_XYZ.mult(2.5f)));
        viewPort.addProcessor(fpp);
    }

    public static void main(String[] args) {
        TestDeferredShading testTileBasedDeferredShading = new TestDeferredShading();
        testTileBasedDeferredShading.start();
    }
}
