/*
 * Copyright (c) 2009-2025 jMonkeyEngine
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
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.SrgbPresentation;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;

/**
 * Explicit SDR encoding example. Requires float color targets and GLSL100 or
 * newer. No external assets. Run --verify or Gradle srgbPresentationSmoke for
 * numeric GPU checks of the sRGB breakpoint, mid-grey, alpha and resize.
 * This is SDR presentation, not HDR10 or automatic tonemapping. No input controls.
 * <pre>{@code
 * // 第一步：保持颜色纹理线性化，建立引擎拥有的浮点合成目标。
 * output = new SrgbPresentation(assetManager, renderer, width, height);
 * viewPort.setOutputFrameBuffer(output.getCompositionTarget());
 * // 第二步：将 HDR 后处理的 Tonemap 结果和 UI 在线性显示空间合成到该目标。
 * // 第三步：所有相关视口完成后调用 output.encode(renderManager)，再读回或呈现。
 * // 编码目标关闭硬件 sRGB 写转换；之后不得再混合未经编码的颜色。
 * // 第四步：恢复视口输出后关闭 output；resize 时重新创建并通知 FPP。
 * }</pre>
 */
public class TestSrgbPresentation extends SimpleApplication {
    private SrgbPresentation output;
    private int frame;
    private com.jme3.system.awt.AwtPanel panel;
    private static boolean verify;

    public static void main(String[] args) {
        verify = args.length > 0 && "--verify".equals(args[0]);
        TestSrgbPresentation app = new TestSrgbPresentation();
        AppSettings settings = new AppSettings(true);
        settings.setResolution(64, 32);
        settings.setAudioRenderer(null);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setGammaCorrection(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        flyCam.setEnabled(false);
        output = new SrgbPresentation(assetManager, renderer, 64, 32);
        viewPort.setOutputFrameBuffer(output.getCompositionTarget());
        viewPort.setBackgroundColor(new ColorRGBA(.18f, .0031308f, .5f, .4f));
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (++frame < 3) {
            return;
        }
        if (frame >= 5) {
            verifyPanel();
            return;
        }
        FrameBuffer previous = renderer.getCurrentFrameBuffer();
        com.jme3.renderer.Camera camera = manager.getCurrentCamera();
        FrameBuffer encoded = output.encode(manager);
        if (encoded.isSrgb() || output.getCompositionTarget().isSrgb()
                || previous != renderer.getCurrentFrameBuffer() || camera != manager.getCurrentCamera()) {
            throw new AssertionError("Presentation state was not restored");
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(encoded.getWidth() * encoded.getHeight() * 4);
        renderer.readFrameBuffer(encoded, pixels);
        int[] expected = {118, 10, 188, 102};
        for (int i = 0; i < 4; i++) {
            int value = pixels.get(i) & 255;
            if (Math.abs(value - expected[i]) > 2) {
                throw new AssertionError("Encoded channel " + i + ": " + value);
            }
        }
        if (frame == 3) {
            viewPort.setOutputFrameBuffer(null);
            output.close();
            output = new SrgbPresentation(assetManager, renderer, 32, 16);
            viewPort.setOutputFrameBuffer(output.getCompositionTarget());
            cam.resize(32, 16, false);
        } else {
            boolean oldSrgb = renderer.isMainFrameBufferSrgb();
            renderer.setMainFrameBufferSrgb(true);
            output.present(manager, null);
            if (!renderer.isMainFrameBufferSrgb()) {
                throw new AssertionError("Main output policy was not restored");
            }
            renderer.setMainFrameBufferSrgb(oldSrgb);
            ByteBuffer window = BufferUtils.createByteBuffer(64 * 32 * 4);
            renderer.readFrameBuffer(null, window);
            if (Math.abs((window.get(0) & 255) - 118) > 2) {
                throw new AssertionError("Main output was double encoded");
            }
            viewPort.setOutputFrameBuffer(null);
            output.close();
            output = null;
            com.jme3.system.awt.AwtPanelsContext panels = new com.jme3.system.awt.AwtPanelsContext();
            panels.setSettings(getContext().getSettings());
            panels.setSystemListener(this);
            panel = panels.createPanel(com.jme3.system.awt.PaintMode.OnRequest, true);
            panel.attachTo(false, viewPort);
            com.jme3.post.FilterPostProcessor fpp = new com.jme3.post.FilterPostProcessor(assetManager);
            fpp.setFrameBufferFormat(com.jme3.texture.Image.Format.RGBA16F);
            fpp.addFilter(new com.jme3.post.filters.FXAAFilter());
            viewPort.getProcessors().add(0, fpp);
        }
    }


    private void verifyPanel() {
        try {
            if (frame == 5) {
                if (!panel.isShaderSrgbOutput()) {
                    throw new AssertionError("AWT did not inherit the default shader output policy");
                }
                panel.setShaderSrgbOutput(assetManager);
                return;
            }
            java.lang.reflect.Method readback = panel.getClass().getDeclaredMethod("readbackTarget");
            readback.setAccessible(true);
            FrameBuffer target = (FrameBuffer) readback.invoke(panel);
            ByteBuffer pixel = BufferUtils.createByteBuffer(target.getWidth() * target.getHeight() * 4);
            renderer.readFrameBuffer(target, pixel);
            int[] expected = {118, 10, 188};
            for (int i = 0; i < 3; i++) {
                if (Math.abs((pixel.get(i) & 255) - expected[i]) > 2) {
                    throw new AssertionError("Panel/FPP channel " + i + ": " + (pixel.get(i) & 255));
                }
            }
            if (frame == 6) {
                panel.setShaderSrgbOutput(null);
            } else if (frame == 7) {
                panel.setShaderSrgbOutput(assetManager);
            } else {
                System.out.println("SRGB_PRESENTATION_GPU_PASS");
                stop();
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Override
    public void destroy() {
        if (output != null) {
            viewPort.setOutputFrameBuffer(null);
            output.close();
        }
        super.destroy();
    }
}
