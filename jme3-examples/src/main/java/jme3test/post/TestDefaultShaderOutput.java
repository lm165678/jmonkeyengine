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
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.system.*;
import com.jme3.system.lwjgl.LwjglOffscreenBuffer;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Verifies the ordinary LWJGL3 window blit path without showing a test window.
 * The custom context forces that path on a hidden drawable; ordinary offscreen
 * applications continue to use raw targets. Requires OpenGL4.3 and float color.
 * Run Gradle defaultShaderOutputSmoke, optionally -PpresentationSamples=4.
 * Success prints DEFAULT_SHADER_OUTPUT_PASS. No external assets or controls.
 * <pre>{@code
 * // 第一步：普通游戏启动前保留默认输出策略，不需要为场景增加 Gamma Filter。
 * settings.setGammaCorrection(true);
 * settings.setShaderSrgbOutput(true);
 * // 第二步：正常绘制场景和 GUI，窗口在全部视口完成后编码。
 * // 第三步：需要硬件优先兼容模式时，启动前设 false，或修改后 restart。
 * }</pre>
 */
public class TestDefaultShaderOutput extends SimpleApplication {
    private int frames;

    public static class HiddenWindowPath extends LwjglOffscreenBuffer {
        @Override
        public void setSettings(AppSettings settings) {
            super.setSettings(settings);
            getSettings().setRenderer(AppSettings.LWJGL_OPENGL43);
        }
        @Override
        protected boolean useBlitFramebuffer() {
            return true;
        }
        @Override
        protected boolean renderFrameWithBlitFramebuffer() {
            boolean rendered = super.renderFrameWithBlitFramebuffer();
            TestDefaultShaderOutput app = (TestDefaultShaderOutput) listener;
            if (rendered && app.frames >= 3) {
                int width = getFramebufferWidth();
                int height = getFramebufferHeight();
                ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
                renderer.readFrameBuffer(null, pixels);
                int center = (height / 2 * width + width / 2) * 4;
                int[] expected = {118, 10, 188};
                for (int i = 0; i < 3; i++) {
                    if (Math.abs((pixels.get(center + i) & 255) - expected[i]) > 2) {
                        throw new AssertionError("Default output was not encoded once");
                    }
                }
                if (renderer.isMainFrameBufferSrgb() || GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB)) {
                    throw new AssertionError("Hardware sRGB remained enabled");
                }
                System.out.println("DEFAULT_SHADER_OUTPUT_PASS samples=" + settings.getSamples());
                app.stop();
            }
            return rendered;
        }
    }

    public static void main(String[] args) {
        TestDefaultShaderOutput app = new TestDefaultShaderOutput();
        AppSettings settings = new AppSettings(true);
        settings.setCustomRenderer(HiddenWindowPath.class);
        settings.setResolution(64, 32);
        settings.setAudioRenderer(null);
        settings.setSamples(Integer.getInteger("presentation.samples", 0));
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }
    @Override
    public void simpleInitApp() {
        getSceneServices().setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        viewPort.setBackgroundColor(new ColorRGBA(.18f, .0031308f, .5f, .4f));
    }
    @Override
    public void simpleRender(RenderManager manager) {
        FrameBuffer target = renderer.getCurrentFrameBuffer();
        if (target == null || target.isSrgb()
                || (target.getColorTarget(0).getFormat() != Image.Format.RGBA16F
                && target.getColorTarget(0).getFormat() != Image.Format.RGBA32F)) {
            throw new AssertionError("Default window path did not compose into an owned float target");
        }
        frames++;
    }
    @Override
    public void handleError(String message, Throwable error) {
        error.printStackTrace();
        System.exit(1);
    }
}
