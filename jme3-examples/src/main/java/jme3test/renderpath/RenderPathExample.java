package jme3test.renderpath;

import com.jme3.app.SimpleApplication;
import com.jme3.system.AppSettings;

/** Common context requirements for the render-path examples. */
public abstract class RenderPathExample extends SimpleApplication {
    protected RenderPathExample() {
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        setSettings(settings);
    }

    @Override
    public void handleError(String message, Throwable error) {
        if (Boolean.getBoolean("renderpath.smoke")) {
            System.err.println(message);
            if (error != null) error.printStackTrace();
            System.exit(1);
        }
        super.handleError(message, error);
    }
}
