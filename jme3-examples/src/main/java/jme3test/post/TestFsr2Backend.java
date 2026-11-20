/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.math.Vector2f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.upscaling.TemporalUpscalingBackend;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.ServiceLoader;

/**
 * Low-level FSR2 backend diagnostic, separate from the recommended UpscalingPass
 * integration shown in TestUpscaling. Requires desktop OpenGL4.3 and float color
 * targets. Run Gradle fsr2BackendSmoke; success prints FSR2_BACKEND_GPU_PASS.
 * No external assets or input controls. Verifies constant HDR color and reset
 * through the AMD compute kernels using TemporalUpscalingBackend's SPI.
 */
public class TestFsr2Backend extends SimpleApplication {
    private TemporalUpscalingBackend backend;
    private Texture2D color, depth, motion;
    private FrameBuffer output;
    private int frame;

    public static void main(String[] args) {
        TestFsr2Backend app = new TestFsr2Backend();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(256, 192);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    private Texture2D input(Image.Format format, float... channels) {
        ByteBuffer data = BufferUtils.createByteBuffer(128 * 96 * channels.length * 4);
        for (int i = 0; i < 128 * 96; i++) {
            for (float value : channels) {
                data.putFloat(value);
            }
        }
        data.flip();
        return new Texture2D(new Image(format, 128, 96, data));
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        backend = ServiceLoader.load(TemporalUpscalingBackend.class).iterator().next();
        color = input(Image.Format.RGBA32F, .18f, .2f, .4f, 1);
        depth = input(Image.Format.R32F, .5f);
        motion = input(Image.Format.RG32F, 0, 0);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        Texture2D reconstructed = backend.reconstruct(manager, color, depth, motion,
                null, null, cam, new Vector2f(), 1f / 60, 256, 192, .2f, frame == 0 || frame == 6);
        if (output == null) {
            output = new FrameBuffer(256, 192, 1);
            output.setSrgb(false);
            output.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(reconstructed));
        }
        ByteBuffer pixel = BufferUtils.createByteBuffer(256 * 192 * 4);
        renderer.readFrameBuffer(output, pixel);
        int position = (96 * 256 + 128) * 4;
        int[] expected = {46, 51, 102};
        for (int i = 0; i < 3; i++) {
            if (Math.abs((pixel.get(position + i) & 255) - expected[i]) > 4) {
                throw new AssertionError("FSR2 channel " + i + ": " + (pixel.get(position + i) & 255));
            }
        }
        if (++frame == 10) {
            System.out.println("FSR2_BACKEND_GPU_PASS");
            stop();
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }

    @Override
    public void destroy() {
        if (output != null) {
            output.dispose();
        }
        if (backend != null) {
            backend.close();
        }
        super.destroy();
    }
}
