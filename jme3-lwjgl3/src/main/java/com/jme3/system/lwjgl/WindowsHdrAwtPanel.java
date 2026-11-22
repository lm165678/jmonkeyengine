package com.jme3.system.lwjgl;

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.SrgbPresentation;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.PaintMode;
import com.jme3.texture.FrameBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTWin32DrawingSurfaceInfo;

import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.system.jawt.JAWTFunctions.*;
import static org.lwjgl.system.windows.User32.*;

/**
 * An AWT canvas with optional HDR presentation in a native, input-disabled
 * child window. The AWT peer remains the input target and owns SDR painting.
 * Construct on the EDT and use an SDL desktop OpenGL context for rendering.
 *
 * <pre>{@code
 * // 第一步：在 AwtPanelsContext.newPanel 中创建该面板。
 * return new WindowsHdrAwtPanel(paintMode, srgb, bitsPerPixel);
 * // 第二步：挂载视口后，在渲染线程启用线性合成和 HDR 预览。
 * panel.setShaderSrgbOutput(assetManager);
 * panel.setHdrPreviewEnabled(true);
 * // 第三步：传 false 恢复 AWT SDR；驱动或显示器不可用时也走 SDR。
 * panel.setHdrPreviewEnabled(false);
 * }</pre>
 */
public final class WindowsHdrAwtPanel extends AwtPanel {
    private static final Logger LOGGER = Logger.getLogger(WindowsHdrAwtPanel.class.getName());

    private boolean requested;
    private boolean failed;
    private volatile boolean childVisible;
    private boolean noHeadroomReported;
    private int peerRevision = -1;
    private int width;
    private int height;
    private long childWindow;
    private long sdlWindow;
    private SdlScRgbPresentation presenter;

    /**
     * Creates a Windows panel with standard SDR presentation until HDR is requested.
     *
     * @param paintMode AWT painting mode used for SDR
     * @param srgb whether SDR output should use an sRGB framebuffer
     * @param bitsPerPixel color depth used for the AWT SDR framebuffer
     */
    public WindowsHdrAwtPanel(PaintMode paintMode, boolean srgb, int bitsPerPixel) {
        super(paintMode, srgb, bitsPerPixel);
    }

    /**
     * Requests native HDR preview from the panel's linear composition.
     * Call on the render thread. By default HDR preview is off.
     *
     * @param enabled true to request HDR preview
     */
    public void setHdrPreviewEnabled(boolean enabled) {
        if (requested != enabled) {
            requested = enabled;
            failed = false;
            if (!enabled) {
                releaseNativePresentation();
            }
        }
    }

    /** @return whether the native HDR preview is visible */
    public boolean isHdrPreviewActive() {
        return childVisible;
    }

    @Override
    protected boolean presentNative(RenderManager manager, SrgbPresentation output) {
        if (!requested) {
            return false;
        }
        int revision = getNativePeerRevision();
        if (peerRevision != revision) {
            releaseNativePresentation();
            peerRevision = revision;
        }
        if (failed) {
            return false;
        }

        FrameBuffer composition = output.getCompositionTarget();
        long glWindow = SDL_GL_GetCurrentWindow();
        long glContext = SDL_GL_GetCurrentContext();
        try {
            if (sdlWindow == 0) {
                createOutput(composition.getWidth(), composition.getHeight());
            }
            float headroom = presenter.getHeadroom();
            if (!Float.isFinite(headroom) || headroom <= 1f) {
                hideChild();
                if (!noHeadroomReported) {
                    LOGGER.info("AWT 3DViewer has no HDR display headroom, using SDR");
                    noHeadroomReported = true;
                }
                return false;
            }
            noHeadroomReported = false;
            resizeChild(composition.getWidth(), composition.getHeight());
            FrameBuffer previous = manager.getRenderer().getCurrentFrameBuffer();
            try {
                presenter.bind(width, height, headroom, previous == null ? 0 : previous.getId());
                output.presentHdr(manager, headroom);
            } finally {
                presenter.unbind();
            }
            presenter.present();
            if (!childVisible) {
                if (!SetWindowPos(null, childWindow, HWND_TOP, 0, 0, width, height,
                        SWP_NOACTIVATE | SWP_SHOWWINDOW)) {
                    throw new IllegalStateException("Cannot show the HDR preview window");
                }
                childVisible = true;
                LOGGER.info("AWT 3DViewer HDR preview active (shared FP16 scRGB)");
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            releaseNativePresentation();
            failed = true;
            LOGGER.log(Level.WARNING, "AWT HDR preview unavailable, using SDR", failure);
            return false;
        } finally {
            if (SDL_GL_GetCurrentContext() != glContext && !SDL_GL_MakeCurrent(glWindow, glContext)) {
                throw new IllegalStateException("Cannot restore the preview OpenGL context: " + SDL_GetError());
            }
        }
    }

    private void createOutput(int width, int height) {
        long parent = getCanvasHandle();
        // A separate HWND keeps the flip swap chain away from Java2D's AWT peer.
        // The disabled child is skipped by Windows hit testing.
        childWindow = CreateWindowEx(null, WS_EX_TRANSPARENT | WS_EX_NOACTIVATE,
                "STATIC", "", WS_CHILD | WS_DISABLED, 0, 0, width, height, parent, 0, 0, 0);
        if (childWindow == 0) {
            throw new IllegalStateException("Cannot create the HDR preview child window");
        }
        int properties = SDL_CreateProperties();
        if (properties == 0) {
            throw new IllegalStateException("Cannot allocate HDR window properties: " + SDL_GetError());
        }
        try {
            if (!SDL_SetPointerProperty(properties, SDL_PROP_WINDOW_CREATE_WIN32_HWND_POINTER, childWindow)) {
                throw new IllegalStateException("Cannot configure the HDR preview window: " + SDL_GetError());
            }
            sdlWindow = SDL_CreateWindowWithProperties(properties);
            if (sdlWindow == 0) {
                throw new IllegalStateException("Cannot attach SDL to the preview HWND: " + SDL_GetError());
            }
        } finally {
            SDL_DestroyProperties(properties);
        }
        presenter = new SdlScRgbPresentation(sdlWindow, false);
        this.width = width;
        this.height = height;
    }

    private void resizeChild(int width, int height) {
        if (this.width != width || this.height != height) {
            if (!SetWindowPos(null, childWindow, 0, 0, 0, width, height, SWP_NOACTIVATE | SWP_NOZORDER)) {
                throw new IllegalStateException("Cannot resize the HDR preview window");
            }
            this.width = width;
            this.height = height;
        }
    }

    private void hideChild() {
        if (childVisible) {
            SetWindowPos(null, childWindow, 0, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOZORDER | SWP_HIDEWINDOW);
            childVisible = false;
        }
    }

    @Override
    protected void releaseNativePresentation() {
        hideChild();
        try {
            if (presenter != null) {
                presenter.close();
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Could not release HDR preview resources", failure);
        } finally {
            presenter = null;
            if (sdlWindow != 0) {
                SDL_DestroyWindow(sdlWindow);
                sdlWindow = 0;
            }
            if (childWindow != 0) {
                DestroyWindow(null, childWindow);
                childWindow = 0;
            }
        }
        failed = false;
        noHeadroomReported = false;
    }

    private long getCanvasHandle() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            JAWT awt = JAWT.calloc(stack).version(JAWT_VERSION_1_4);
            if (!JAWT_GetAWT(awt)) {
                throw new IllegalStateException("JAWT is unavailable");
            }
            JAWTDrawingSurface surface = JAWT_GetDrawingSurface(this, awt.GetDrawingSurface());
            if (surface == null) {
                throw new IllegalStateException("AWT preview has no native surface");
            }
            try {
                if ((JAWT_DrawingSurface_Lock(surface, surface.Lock()) & JAWT_LOCK_ERROR) != 0) {
                    throw new IllegalStateException("Cannot lock the AWT preview surface");
                }
                try {
                    JAWTDrawingSurfaceInfo info = JAWT_DrawingSurface_GetDrawingSurfaceInfo(
                            surface, surface.GetDrawingSurfaceInfo());
                    if (info == null) {
                        throw new IllegalStateException("AWT preview surface information is unavailable");
                    }
                    try {
                        JAWTWin32DrawingSurfaceInfo win32 = JAWTWin32DrawingSurfaceInfo.createSafe(info.platformInfo());
                        if (win32 == null || win32.hwnd() == 0) {
                            throw new IllegalStateException("AWT preview HWND is unavailable");
                        }
                        return win32.hwnd();
                    } finally {
                        JAWT_DrawingSurface_FreeDrawingSurfaceInfo(info, surface.FreeDrawingSurfaceInfo());
                    }
                } finally {
                    JAWT_DrawingSurface_Unlock(surface, surface.Unlock());
                }
            } finally {
                JAWT_FreeDrawingSurface(surface, awt.FreeDrawingSurface());
            }
        }
    }
}
