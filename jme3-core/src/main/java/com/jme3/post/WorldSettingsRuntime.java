package com.jme3.post;

import com.jme3.asset.AssetManager;
import com.jme3.renderer.Caps;
import com.jme3.renderer.ViewPort;
import com.jme3.texture.Image;
import java.io.IOException;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies scene post-processing settings to the main viewport on the application thread.
 * Never use this for baking/capture viewports. One instance per viewport.
 * <pre>{@code
 * // 第一步：非 SimpleApplication 创建管理器；SimpleApplication 已自动管理。
 * WorldSettingsRuntime runtime = new WorldSettingsRuntime();
 * // 第二步：每帧在渲染前同步；参数为当前挂载场景的配置，移除时传 null。
 * runtime.sync(settings, assetManager, viewPort, renderer.getCaps());
 * // 第三步：关闭视口或改为手动 FPP 管理前释放。
 * runtime.close();
 * }</pre>
 */
public final class WorldSettingsRuntime implements AutoCloseable {
    private WorldSettings source;
    private long revision = -1;
    private ViewPort view;
    private FilterPostProcessor processor;

    /** Existing manual FPPs win. Failed configuration is retried after its revision changes. */
    public void sync(WorldSettings settings, AssetManager assets, ViewPort target,
            Collection<Caps> caps) {
        boolean supported = caps.contains(Caps.HalfFloatColorBufferRGBA)
                || caps.contains(Caps.FloatColorBufferRGBA);
        boolean other = false;
        for (var candidate : target.getProcessors()) {
            if (candidate instanceof FilterPostProcessor && candidate != processor) {
                other = true;
            }
        }
        if (settings == null || !settings.isEnabled() || !supported || other) {
            close();
            return;
        }
        if (source == settings && revision == settings.getRevision() && view == target) {
            return;
        }
        close();
        source = settings;
        revision = settings.getRevision();
        view = target;
        try {
            processor = settings.createPostProcessor(assets);
            if (processor != null) {
                processor.setFrameBufferFormat(caps.contains(Caps.HalfFloatColorBufferRGBA)
                        ? Image.Format.RGBA16F : Image.Format.RGBA32F);
                target.addProcessor(processor);
            }
        } catch (IOException | RuntimeException exception) {
            Logger.getLogger(WorldSettingsRuntime.class.getName()).log(Level.WARNING,
                    "Cannot restore scene post-processing", exception);
        }
    }

    @Override
    public void close() {
        if (processor != null && view != null) {
            if (view.getProcessors().contains(processor)) {
                view.removeProcessor(processor);
            }
        }
        processor = null;
        view = null;
        source = null;
        revision = -1;
    }
}
