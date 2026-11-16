/*
 * Copyright (c) 2009-2021 jMonkeyEngine
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
package com.jme3.system.awt;

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import com.jme3.util.Screenshots;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AwtPanel extends Canvas implements SceneProcessor {

    private static final Logger logger = Logger.getLogger(AwtPanel.class.getName());

    private boolean attachAsMain = false;

    private BufferedImage img;
    private FrameBuffer fb;
    private boolean srgb = false;
    private ByteBuffer byteBuf;
    private IntBuffer intBuf;
    private RenderManager rm;
    private PaintMode paintMode;
    private final java.util.List<ViewPort> viewPorts = new ArrayList<>();

    // Visibility/drawing vars
    private BufferStrategy strategy;
    private final AtomicBoolean hasNativePeer = new AtomicBoolean(false);
    private final AtomicBoolean showing = new AtomicBoolean(false);
    private final AtomicBoolean repaintRequest = new AtomicBoolean(false);

    // Reshape vars
    private volatile int renderedWidth = 1;
    private volatile int renderedHeight = 1;
    private int newWidth = 1;
    private int newHeight = 1;
    private final AtomicBoolean reshapeNeeded = new AtomicBoolean(false);
    private final Object lock = new Object();

    public AwtPanel(PaintMode paintMode) {
        this(paintMode, false);
    }

    public AwtPanel(PaintMode paintMode, boolean srgb) {
        this.paintMode = paintMode;
        this.srgb = srgb;
        if (paintMode == PaintMode.Accelerated) {
            setIgnoreRepaint(true);
        }

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateRenderSize();
            }
            @Override
            public void componentMoved(ComponentEvent event) {
                updateRenderSize();
            }
        });
        addPropertyChangeListener("graphicsConfiguration", event -> updateRenderSize());
    }

    /**
     * Converts logical AWT X units to the current framebuffer's device pixels.
     * AwtMouseInput uses this automatically; manual picking must scale exactly once.
     * At 100% the ratio is one. A panel without a display uses logical pixels.
     * <pre>{@code
     * // 第一步：AWT 面板按显示器缩放创建物理像素帧缓冲，无需禁用系统 DPI。
     * // 第二步：手工处理 AWT 点击时转换为与 Camera 相同的像素坐标。
     * float x = (float) (event.getX() * panel.getFramebufferScaleX());
     * float y = (float) ((panel.getHeight() - event.getY()) * panel.getFramebufferScaleY());
     * // 第三步：跨屏移动或调整窗口会自动重建；不要再次缩放引擎鼠标事件。
     * }</pre>
     * @return framebuffer pixels per logical X unit
     */
    public double getFramebufferScaleX() {
        return renderedWidth / (double) Math.max(1, getWidth());
    }

    /**
     * Y counterpart of getFramebufferScaleX(), for manual AWT picking.
     * @return framebuffer pixels per logical Y unit
     */
    public double getFramebufferScaleY() {
        return renderedHeight / (double) Math.max(1, getHeight());
    }

    private void updateRenderSize() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        AffineTransform scale = configuration == null ? new AffineTransform() : configuration.getDefaultTransform();
        int width = Math.max(1, (int) Math.round(getWidth() * scale.getScaleX()));
        int height = Math.max(1, (int) Math.round(getHeight() * scale.getScaleY()));
        synchronized (lock) {
            if (width != newWidth || height != newHeight) {
                newWidth = width;
                newHeight = height;
                reshapeNeeded.set(true);
            }
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        updateRenderSize();

        synchronized (lock) {
            hasNativePeer.set(true);
        }

        requestFocusInWindow();
    }

    @Override
    public void removeNotify() {
        synchronized (lock) {
            hasNativePeer.set(false);
        }

        super.removeNotify();
    }

    @Override
    public void paint(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        synchronized (lock) {
            if (img != null) {
                g2d.drawImage(img, 0, getHeight(), getWidth(), 0,
                        0, 0, img.getWidth(), img.getHeight(), null);
            }
        }
    }

    public boolean checkVisibilityState() {
        if (!hasNativePeer.get()) {
            if (strategy != null) {
//                strategy.dispose();
                strategy = null;
            }
            return false;
        }

        boolean currentShowing = isShowing();
        showing.set(currentShowing);
        return currentShowing;
    }

    public void repaintInThread() {
        if (!isInitialized()) {
            return;
        }
        // Convert screenshot.
        byteBuf.clear();
        rm.getRenderer().readFrameBuffer(fb, byteBuf);

        synchronized (lock) {
            // All operations on img must be synchronized
            // as it is accessed from EDT.
            Screenshots.convertScreenShot2(intBuf, img);
            repaint();
        }
    }

    public void drawFrameInThread() {
        if (!isInitialized()) {
            return;
        }
        // Convert screenshot.
        byteBuf.clear();
        rm.getRenderer().readFrameBuffer(fb, byteBuf);
        Screenshots.convertScreenShot2(intBuf, img);

        synchronized (lock) {
            // All operations on strategy should be synchronized (?)
            if (strategy == null) {
                try {
                    createBufferStrategy(1,
                            new BufferCapabilities(
                                    new ImageCapabilities(true),
                                    new ImageCapabilities(true),
                                    BufferCapabilities.FlipContents.UNDEFINED)
                    );
                } catch (AWTException ex) {
                    logger.log(Level.WARNING, "Failed to create buffer strategy!", ex);
                }
                strategy = getBufferStrategy();
            }

            // Draw screenshot.
            do {
                do {
                    Graphics2D g2d = (Graphics2D) strategy.getDrawGraphics();
                    if (g2d == null) {
                        logger.log(Level.WARNING, "OGL: DrawGraphics was null.");
                        return;
                    }

                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_SPEED);

                    if (img != null) {
                g2d.drawImage(img, 0, getHeight(), getWidth(), 0,
                        0, 0, img.getWidth(), img.getHeight(), null);
            }
                    g2d.dispose();
                    strategy.show();
                } while (strategy.contentsRestored());
            } while (strategy.contentsLost());
        }
    }

    public boolean isActiveDrawing() {
        return paintMode != PaintMode.OnRequest && showing.get();
    }

    /**
     * Attach on the render thread. A panel may become visible on EDT before the
     * first viewport render calls initialize; those early frames are skipped and
     * pending size changes retained. Visibility alone never initializes a panel.
     * <pre>{@code
     * // 第一步：EDT 创建并加入 AWT 面板；尚未绑定视口时不会读取帧缓冲。
     * // 第二步：在渲染线程绑定视口，第一次渲染自动完成初始化。
     * application.enqueue(() -> panel.attachTo(true, application.getViewPort()));
     * // 第三步：EDT 调整面板大小；初始化前的尺寸变化也会在就绪后生效。
     * }</pre>
     * @param overrideMainFramebuffer whether to replace the default framebuffer
     * @param vps viewports to display, ending with the viewport receiving this processor
     */
    public void attachTo(boolean overrideMainFramebuffer, ViewPort... vps) {
        if (!viewPorts.isEmpty()) {
            for (ViewPort vp : viewPorts) {
                vp.setOutputFrameBuffer(null);
            }
            viewPorts.get(viewPorts.size() - 1).removeProcessor(this);
        }

        viewPorts.addAll(Arrays.asList(vps));
        viewPorts.get(viewPorts.size() - 1).addProcessor(this);

        this.attachAsMain = overrideMainFramebuffer;
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        if (this.rm == null) {
            // First time called in OGL thread
            this.rm = rm;
            reshapeInThread(1, 1);
        }
    }

    private void reshapeInThread(int width, int height) {
        byteBuf = BufferUtils.ensureLargeEnough(byteBuf, width * height * 4);
        intBuf = byteBuf.asIntBuffer();

        if (fb != null) {
            fb.dispose();
            fb = null;
        }

        fb = new FrameBuffer(width, height, 1);
        fb.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.Depth));
        fb.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.RGB8));
        fb.setSrgb(srgb);

        if (attachAsMain) {
            rm.getRenderer().setMainFrameBufferOverride(fb);
        }

        synchronized (lock) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
        }

        renderedWidth = width;
        renderedHeight = height;

        for (ViewPort vp : viewPorts) {
            if (!attachAsMain) {
                vp.setOutputFrameBuffer(fb);
            }
            vp.getCamera().resize(width, height, true);

            // NOTE: Hack alert. This is done ONLY for custom framebuffers.
            // Main framebuffer should use RenderManager.notifyReshape().
            for (SceneProcessor sp : vp.getProcessors()) {
                sp.reshape(vp, width, height);
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return rm != null && fb != null;
    }

    @Override
    public void preFrame(float tpf) {
    }

    @Override
    public void postQueue(RenderQueue rq) {
    }

    @Override
    public void invalidate() {
        // For "PaintMode.OnDemand" only.
        repaintRequest.set(true);
    }

    void onFrameEnd() {
        // createPanel publishes to the context before the enqueued attachTo runs.
        // Never allocate fb early: doing so previously made isInitialized() true
        // and prevented the viewport from ever supplying the RenderManager.
        if (!isInitialized()) {
            return;
        }
        // A monitor-scale change may happen without a logical component resize.
        updateRenderSize();
        if (reshapeNeeded.getAndSet(false)) {
            int width;
            int height;
            synchronized (lock) {
                width = newWidth;
                height = newHeight;
            }
            reshapeInThread(width, height);
        } else {
            if (!checkVisibilityState()) {
                return;
            }

            switch (paintMode) {
                case Accelerated:
                    drawFrameInThread();
                    break;
                case Repaint:
                    repaintInThread();
                    break;
                case OnRequest:
                    if (repaintRequest.getAndSet(false)) {
                        repaintInThread();
                    }
                    break;
            }
        }
    }

    @Override
    public void postFrame(FrameBuffer out) {
        if (!attachAsMain && out != fb) {
            throw new IllegalStateException("Why did you change the output framebuffer?");
        }

        // onFrameEnd();
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
        // not implemented
    }
}
