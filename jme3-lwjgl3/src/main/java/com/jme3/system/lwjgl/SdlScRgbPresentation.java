package com.jme3.system.lwjgl;

import org.lwjgl.opengl.GL;
import org.lwjgl.sdl.SDL_Texture;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.WGLNVDXInterop.*;
import static org.lwjgl.sdl.SDLBlendMode.SDL_BLENDMODE_NONE;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLPixels.*;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.sdl.SDLRender.*;
import static org.lwjgl.sdl.SDLSurface.SDL_FLIP_VERTICAL;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.windows.WinBase.GetLastError;

/**
 * Presents a linear OpenGL composition through an SDL/D3D11 scRGB swap chain
 * using a shared FP16 render target.
 * All operations run on the window thread that owns the OpenGL context.
 */
final class SdlScRgbPresentation implements AutoCloseable {
    private final long renderer;
    private final int properties;
    private final long d3dDevice;
    private final long interopDevice;
    private SDL_Texture texture;
    private long interopTexture;
    private int framebuffer;
    private int renderbuffer;
    private int previousFramebuffer;
    private boolean locked;
    private int width;
    private int height;
    private float textureHeadroom;

    SdlScRgbPresentation(long window, boolean vsync) {
        if (!GL.getCapabilitiesWGL().WGL_NV_DX_interop2) {
            throw new IllegalStateException("WGL_NV_DX_interop2 is unavailable");
        }
        int creation = SDL_CreateProperties();
        require(creation != 0, "create renderer properties");
        try {
            require(SDL_SetStringProperty(creation, SDL_PROP_RENDERER_CREATE_NAME_STRING, "direct3d11")
                    && SDL_SetPointerProperty(creation, SDL_PROP_RENDERER_CREATE_WINDOW_POINTER, window)
                    && SDL_SetNumberProperty(creation, SDL_PROP_RENDERER_CREATE_OUTPUT_COLORSPACE_NUMBER,
                            SDL_COLORSPACE_SRGB_LINEAR), "configure scRGB renderer");
            renderer = SDL_CreateRendererWithProperties(creation);
            require(renderer != NULL, "create scRGB renderer");
        } finally {
            SDL_DestroyProperties(creation);
        }
        properties = SDL_GetRendererProperties(renderer);
        if (SDL_GetNumberProperty(properties, SDL_PROP_RENDERER_OUTPUT_COLORSPACE_NUMBER, 0)
                != SDL_COLORSPACE_SRGB_LINEAR || !SDL_SetRenderVSync(renderer, vsync ? 1 : 0)) {
            String error = SDL_GetError();
            SDL_DestroyRenderer(renderer);
            throw new IllegalStateException("Unable to configure D3D11 scRGB output: " + error);
        }
        d3dDevice = SDL_GetPointerProperty(properties, SDL_PROP_RENDERER_D3D11_DEVICE_POINTER, NULL);
        interopDevice = d3dDevice != NULL ? wglDXOpenDeviceNV(d3dDevice) : NULL;
        if (interopDevice == NULL) {
            int error = GetLastError();
            SDL_DestroyRenderer(renderer);
            throw new IllegalStateException("Unable to open WGL/D3D11 interop device: " + error);
        }
    }

    float getHeadroom() {
        if (!SDL_GetBooleanProperty(properties, SDL_PROP_RENDERER_HDR_ENABLED_BOOLEAN, false)) {
            return 1f;
        }
        return SDL_GetFloatProperty(properties, SDL_PROP_RENDERER_HDR_HEADROOM_FLOAT, 1f);
    }

    float getSdrWhitePoint() {
        return SDL_GetFloatProperty(properties, SDL_PROP_RENDERER_SDR_WHITE_POINT_FLOAT, 1f);
    }

    void bind(int width, int height, float headroom) {
        bind(width, height, headroom, 0);
    }

    void bind(int width, int height, float headroom, int restoreFramebuffer) {
        if (SDL_GetPointerProperty(properties, SDL_PROP_RENDERER_D3D11_DEVICE_POINTER, NULL) != d3dDevice) {
            throw new IllegalStateException("SDL replaced its D3D11 device");
        }
        if (texture == null || this.width != width || this.height != height || textureHeadroom != headroom) {
            allocateTarget(width, height, headroom);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            requireInterop(wglDXLockObjectsNV(interopDevice, stack.pointers(interopTexture)), "lock shared FP16 texture");
        }
        locked = true;
        previousFramebuffer = restoreFramebuffer;
        // Keep the temporary binding local so GLRenderer's framebuffer cache stays valid.
        if (framebuffer == 0) {
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, renderbuffer);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete shared FP16 framebuffer: " + status);
            }
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        }
    }

    void unbind() {
        if (locked) {
            glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                requireInterop(wglDXUnlockObjectsNV(interopDevice, stack.pointers(interopTexture)),
                        "unlock shared FP16 texture");
            }
            locked = false;
        }
    }

    void present() {
        // The OpenGL and SDL texture coordinate systems have opposite Y origins.
        require(SDL_RenderTextureRotated(renderer, texture, null, null, 0, null, SDL_FLIP_VERTICAL),
                "draw FP16 frame");
        require(SDL_RenderPresent(renderer), "present scRGB frame");
    }

    private void allocateTarget(int width, int height, float headroom) {
        releaseTarget();
        int creation = SDL_CreateProperties();
        require(creation != 0, "create texture properties");
        try {
            require(SDL_SetNumberProperty(creation, SDL_PROP_TEXTURE_CREATE_FORMAT_NUMBER, SDL_PIXELFORMAT_RGBA64_FLOAT)
                    && SDL_SetNumberProperty(creation, SDL_PROP_TEXTURE_CREATE_ACCESS_NUMBER, SDL_TEXTUREACCESS_TARGET)
                    && SDL_SetNumberProperty(creation, SDL_PROP_TEXTURE_CREATE_WIDTH_NUMBER, width)
                    && SDL_SetNumberProperty(creation, SDL_PROP_TEXTURE_CREATE_HEIGHT_NUMBER, height)
                    && SDL_SetNumberProperty(creation, SDL_PROP_TEXTURE_CREATE_COLORSPACE_NUMBER, SDL_COLORSPACE_SRGB_LINEAR)
                    && SDL_SetFloatProperty(creation, SDL_PROP_TEXTURE_CREATE_SDR_WHITE_POINT_FLOAT, 1f)
                    && SDL_SetFloatProperty(creation, SDL_PROP_TEXTURE_CREATE_HDR_HEADROOM_FLOAT, headroom),
                    "configure shared FP16 texture");
            texture = SDL_CreateTextureWithProperties(renderer, creation);
            require(texture != null, "create shared FP16 texture");
        } finally {
            SDL_DestroyProperties(creation);
        }
        require(SDL_SetTextureBlendMode(texture, SDL_BLENDMODE_NONE), "disable shared texture blending");
        long d3dTexture = SDL_GetPointerProperty(SDL_GetTextureProperties(texture),
                SDL_PROP_TEXTURE_D3D11_TEXTURE_POINTER, NULL);
        require(d3dTexture != NULL, "get D3D11 FP16 texture");
        renderbuffer = glGenRenderbuffers();
        interopTexture = wglDXRegisterObjectNV(interopDevice, d3dTexture, renderbuffer,
                GL_RENDERBUFFER, WGL_ACCESS_WRITE_DISCARD_NV);
        requireInterop(interopTexture != NULL, "register shared FP16 texture");
        this.width = width;
        this.height = height;
        textureHeadroom = headroom;
    }

    private void releaseTarget() {
        unbind();
        if (interopTexture != NULL) {
            requireInterop(wglDXUnregisterObjectNV(interopDevice, interopTexture), "unregister shared FP16 texture");
            interopTexture = NULL;
        }
        if (framebuffer != 0) {
            glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (renderbuffer != 0) {
            glDeleteRenderbuffers(renderbuffer);
            renderbuffer = 0;
        }
        if (texture != null) {
            SDL_DestroyTexture(texture);
            texture = null;
        }
    }

    @Override
    public void close() {
        try {
            releaseTarget();
        } finally {
            wglDXCloseDeviceNV(interopDevice);
            SDL_DestroyRenderer(renderer);
        }
    }

    private static void require(boolean success, String operation) {
        if (!success) {
            throw new IllegalStateException(operation + ": " + SDL_GetError());
        }
    }

    private static void requireInterop(boolean success, String operation) {
        if (!success) {
            throw new IllegalStateException(operation + ": Windows error " + GetLastError());
        }
    }
}
