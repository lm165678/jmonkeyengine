package com.jme3.renderer.pipeline;

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;

/**
 * Runs the custom FrameGraph stages within the upstream pipeline lifecycle.
 * This is the manager's default pipeline. An explicit viewport pipeline still
 * takes precedence. GPU Scene and deferred rendering retain their capability
 * fallbacks and per-viewport ownership; opaque strategies use OpaquePipeline.
 * All switching and rendering must occur on the application render thread.
 * <pre>{@code
 * // 第一步：默认使用 FrameGraph，保留 GPUScene 和延迟渲染的自动选择。
 * renderManager.setPipeline(new FrameGraphPipeline());
 * // 第二步：需要官方普通前向路径时，切换整帧管线。
 * renderManager.setPipeline(new ForwardPipeline());
 * // 第三步：恢复 FrameGraph；独立视口覆盖可用 viewPort.setPipeline(null) 移除。
 * renderManager.setPipeline(new FrameGraphPipeline());
 * }</pre>
 */
public final class FrameGraphPipeline implements RenderPipeline<PipelineContext> {
    private boolean rendered;

    @Override
    public PipelineContext fetchPipelineContext(RenderManager manager) {
        return manager.getDefaultContext();
    }

    @Override
    public boolean hasRenderedThisFrame() {
        return rendered;
    }

    @Override
    public void startRenderFrame(RenderManager manager) {
    }

    @Override
    public void pipelineRender(RenderManager manager, PipelineContext context,
            ViewPort viewport, float tpf) {
        manager.renderFrameGraphViewPort(viewport, tpf);
        rendered = true;
    }

    @Override
    public void endRenderFrame(RenderManager manager) {
        rendered = false;
    }
}
