package jme3test.renderpath;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;

/**
 * Shared entry point for the migrated examples. Run a concrete class's main method
 * using OpenGL 4.3 for GPU culling (CPU deferred needs OpenGL 3.3 and five MRTs).
 * Call useRenderPath from initialization/input callbacks on the render thread.
 * It installs DeferredPipeline/ForwardPipeline through RenderManager.setPipeline;
 * DeferredPipeline registers its passes in the shared viewport FrameGraph. See RenderPathHelper.apply
 * for the complete public API sequence, including shared model registry and tile size.
 * To use the manager's legacy global selection instead, first remove the override:
 * renderManager.setPipeline(viewPort, null), then setRenderPath(...).
 */
public abstract class RenderPathExample extends SimpleApplication {
    protected RenderPathExample() {
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        setSettings(settings);
    }

    /**
     * Switches this example's viewport through the public Pipeline API.
     * @param path concrete requested path (Forward, Deferred, CPU, GPU or Auto)
     */
    protected final void useRenderPath(com.jme3.renderer.RenderPath path) {
        RenderPathHelper.apply(this, path);
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (Boolean.getBoolean("renderpath.smoke")) {
            System.err.println(message);
            if (error != null) {
                error.printStackTrace();
            }
            System.exit(1);
        }
        super.handleError(message, error);
    }
}
