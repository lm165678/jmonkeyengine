package com.jme3.post;

import com.jme3.material.Material;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.texture.Texture;

/**
 * A composable graph-native Filter. Add to an ordinary FilterPostProcessor;
 * legacy and graph-native filters can be interleaved. initFilter creates materials,
 * while build declares per-frame resources/passes and returns the next color image.
 * No legacy default framebuffer is allocated. Graph resources belong to the viewport.
 * Never retain handles between frames, execute/close the graph, or dispose its targets.
 * The active filter list is sampled when the chain is declared; change settings in
 * preFrame or application updates, not halfway through executing a chain.
 *
 * <pre>{@code
 * // 第一步：继承 FrameGraphFilter，在 initFilter 中加载 j3md 材质。
 * // 第二步：在 build 中声明临时目标和绘制；不要手动 new FrameBuffer。
 * protected ResourceHandle<RenderTarget> build(FilterGraphContext context,
 *         ResourceHandle<RenderTarget> input) {
 *     ResourceHandle<RenderTarget> output = context.createColor("Result", 1f,
 *             Image.Format.RGBA16F);
 *     context.draw("ColorPass", material, input, output);
 *     // 第三步：返回输出句柄，FPP 自动把它交给下一个 Filter。
 *     return output;
 * }
 * // 第四步：与旧 Filter 混用；开关、排序、移除仍用原有 API。
 * fpp.addFilter(new MyGraphFilter());
 * fpp.addFilter(new FXAAFilter());
 * viewPort.addProcessor(fpp);
 * }</pre>
 * For depth, override isRequiresDepthTexture() to return true and use
 * FilterGraphContext.getDepth(). It always represents original scene depth, not
 * the depth of a downsampled intermediate color target. Returning input is a bypass.
 * See jme3test.renderpath.TestFrameGraphFilters for the full runnable example.
 */
public abstract class FrameGraphFilter extends Filter {
    protected FrameGraphFilter(String name) {
        super(name);
    }

    /**
     * Declares work in the shared viewport graph; no rendering during declaration.
     * @param context frame-local builder and fullscreen draw helpers
     * @param input previous enabled filter's color image (may be multisampled)
     * @return output color handle, or input for bypass; never null
     */
    protected abstract ResourceHandle<RenderTarget> build(
        FilterGraphContext context, ResourceHandle<RenderTarget> input);

    @Override
    protected final Material getMaterial() {
        throw new UnsupportedOperationException("FrameGraphFilter declares materials in build()");
    }

    @Override
    protected final void setDepthTexture(Texture texture) {
        // Depth is provided as a declared graph handle, not assigned to one implicit material.
    }
}
