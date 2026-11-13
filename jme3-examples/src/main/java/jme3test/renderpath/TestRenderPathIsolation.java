package jme3test.renderpath;

import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.deferred.TiledDeferredProcessor;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;

/**
 Separate local light scopes and independent viewport lifecycle regression.
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
 * -PrenderPathExample=TestRenderPathIsolation. Checks CPU/GPU pixels and Forward round trips;
 * renderPipelineSuite additionally checks direct Pipeline installation.</p>
 */
public class TestRenderPathIsolation extends RenderPathExample {
    private ViewPort secondary;
    private FrameBuffer target;
    private int frame;

    @Override
    public void simpleInitApp() {
        cam.setLocation(new Vector3f(0, 0, 10));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        for (int i = 0; i < 3; i++) {
            Node scope = new Node("scope " + i);
            Geometry geometry = new Geometry("local light " + i, new Box(.8f, .8f, .8f));
            Material material = new Material(assetManager,
                i == 2 ? "Materials/Tiled/CustomLambert.j3md"
                       : "Common/MatDefs/Light/Lighting.j3md");
            geometry.setMaterial(material);
            geometry.setLocalTranslation((i - 1) * 2.2f, 0, 0);
            scope.attachChild(geometry);
            scope.addLight(new DirectionalLight(new Vector3f(-1, -1, -1).normalizeLocal(),
                i == 0       ? ColorRGBA.Red
                    : i == 1 ? ColorRGBA.Blue
                             : ColorRGBA.Green));
            rootNode.attachChild(scope);
        }
        createSecondary();
        new RenderPathHelper(this);
    }

    private void createSecondary() {
        Camera camera = cam.clone();
        camera.resize(97, 73, true);
        target = new FrameBuffer(97, 73, 1);
        target.setColorBuffer(Image.Format.RGBA8);
        target.setDepthBuffer(Image.Format.Depth24);
        secondary = renderManager.createPreView("independent CPU view", camera);
        secondary.setOutputFrameBuffer(target);
        secondary.setClearFlags(true, true, true);
        secondary.setRenderPath(RenderPath.TiledDeferredCPU);
        secondary.attachScene(rootNode);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (!Boolean.getBoolean("renderpath.smoke")) {
            return;
        }
        TiledDeferredProcessor pipeline = manager.getRenderPipeline(secondary);
        if (pipeline == null
            || pipeline.getActiveMode() != TiledDeferredProcessor.Mode.TiledDeferredCPU
            || pipeline.getDeferredGeometryCount() != 3) {
            throw new AssertionError("Viewport override/local light scope was lost");
        }
        if (pipeline == manager.getRenderPipeline(viewPort)) {
            throw new AssertionError("Viewports share pipeline resources");
        }
        if (++frame == 200) {
            manager.removePreView(secondary);
            if (manager.getRenderPipeline(secondary) != null) {
                throw new AssertionError("Viewport pipeline leaked");
            }
            renderer.deleteFrameBuffer(target);
            createSecondary();
        }
    }

    @Override
    public void destroy() {
        if (secondary != null) {
            renderManager.removePreView(secondary);
        }
        if (target != null) {
            renderer.deleteFrameBuffer(target);
        }
        super.destroy();
    }

    public static void main(String[] args) {
        new TestRenderPathIsolation().start();
    }
}
