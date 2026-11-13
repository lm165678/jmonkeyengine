package jme3test.renderpath;

import com.jme3.renderer.RenderPath;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;

/**
 * Reusable example input binding. SPACE (or the constructor's custom key) cycles
 * Forward -> Deferred -> CPU tiled -> GPU tiled. apply() is the complete render-thread
 * API example; shaders come from MaterialDef, not application-side .frag loading.
 */
public class RenderPathHelper implements ActionListener {
    private SimpleApplication application;
    /**
     * Installs/reconfigures a viewport-owned pipeline, preserving the shared custom
     * model registry. RenderManager owns initialization, resize and cleanup.
     * @param app example application
     * @param path requested concrete path
     */
    public static void apply(SimpleApplication app, RenderPath path) {
        RenderManager manager = app.getRenderManager();
        com.jme3.renderer.ViewPort viewport = app.getViewPort();
        manager.setRenderPath(path);
        if (path == RenderPath.Forward) {
            if (!(manager.getPipeline(viewport)
                        instanceof com.jme3.renderer.pipeline.OpaqueForwardPipeline)) {
                manager.setPipeline(viewport, new com.jme3.renderer.pipeline.OpaqueForwardPipeline());
            }
        } else {
            com.jme3.renderer.pipeline.OpaquePipeline installed = manager.getPipeline(viewport);
            com.jme3.renderer.deferred.DeferredPipeline pipeline;
            if (installed instanceof com.jme3.renderer.deferred.DeferredPipeline) {
                pipeline = (com.jme3.renderer.deferred.DeferredPipeline) installed;
            } else {
                pipeline = new com.jme3.renderer.deferred.DeferredPipeline(
                    app.getAssetManager(), manager.getDeferredShadingModels());
                manager.setPipeline(viewport, pipeline);
            }
            pipeline.setTileSize(manager.getDeferredTileSize());
            pipeline.setLightCullingMode(path == RenderPath.Deferred
                    ? com.jme3.renderer.deferred.LightCullingMode.None
                    : path == RenderPath.TiledDeferredCPU
                    ? com.jme3.renderer.deferred.LightCullingMode.CPU
                    : path == RenderPath.TiledDeferredGPU
                    ? com.jme3.renderer.deferred.LightCullingMode.GPU
                    : com.jme3.renderer.deferred.LightCullingMode.Auto);
        }
    }
    public static float randomFloat() {
        return com.jme3.math.FastMath.nextRandomFloat();
    }
    public static float randomFloat(float min, float max) {
        return min + (max - min) * randomFloat();
    }
    public static RenderPath next(RenderPath current) {
        switch (current) {
            case Forward:
                return RenderPath.Deferred;
            case Deferred:
                return RenderPath.TiledDeferredCPU;
            case TiledDeferredCPU:
                return RenderPath.TiledDeferredGPU;
            default:
                return RenderPath.Forward;
        }
    }
    private RenderPath currentRenderPath;
    private BitmapText hitText;
    private RenderManager renderManager;
    private Vector3f hitLocation = new Vector3f(0, 0, 0);
    private String keyHit = "SPACE";
    private int keyInput = KeyInput.KEY_SPACE;
    public RenderPathHelper(SimpleApplication simpleApplication) {
        application = simpleApplication;
        renderManager = simpleApplication.getRenderManager();
        currentRenderPath = renderManager.getRenderPath();
        hitLocation.set(0, simpleApplication.getCamera().getHeight(), 0);
        makeHudText(simpleApplication);
        simpleApplication.getInputManager().addListener(this, "toggleRenderPath");
        simpleApplication.getInputManager().addMapping(
            "toggleRenderPath", new KeyTrigger(keyInput));
    }

    public RenderPathHelper(
        SimpleApplication simpleApplication, Vector3f hitLocation, int keyInput, String keyHit) {
        application = simpleApplication;
        renderManager = simpleApplication.getRenderManager();
        currentRenderPath = renderManager.getRenderPath();
        this.hitLocation.set(hitLocation);
        this.keyInput = keyInput;
        this.keyHit = keyHit;
        makeHudText(simpleApplication);
        simpleApplication.getInputManager().addListener(this, "toggleRenderPath");
        simpleApplication.getInputManager().addMapping(
            "toggleRenderPath", new KeyTrigger(keyInput));
    }

    private void makeHudText(SimpleApplication simpleApplication) {
        BitmapFont guiFont =
            simpleApplication.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        hitText = new BitmapText(guiFont, false);
        hitText.setSize(guiFont.getCharSet().getRenderedSize());
        hitText.setText("RendererPath : " + currentRenderPath.getInfo());
        hitText.setLocalTranslation(hitLocation);
        simpleApplication.getGuiNode().attachChild(hitText);

        // hit text
        BitmapText title = new BitmapText(guiFont, false);
        title.setSize(guiFont.getCharSet().getRenderedSize());
        title.setText("Please press the " + keyHit + " to toggle the render path");
        title.setLocalTranslation(hitLocation);
        title.getLocalTranslation().y -= 20;
        simpleApplication.getGuiNode().attachChild(title);
    }
    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        if (name.equals("toggleRenderPath") && !isPressed) {
            currentRenderPath = next(currentRenderPath);
            apply(application, currentRenderPath);
            hitText.setText("RendererPath : " + currentRenderPath.getInfo());
        }
    }
}
