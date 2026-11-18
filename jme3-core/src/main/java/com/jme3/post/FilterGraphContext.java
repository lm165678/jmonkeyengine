package com.jme3.post;

import com.jme3.material.Material;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.framegraph.FrameGraph;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.framegraph.RenderTargetDefinition;
import com.jme3.renderer.framegraph.ResourceDefinition;
import com.jme3.renderer.framegraph.ResourceHandle;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import java.util.Objects;

/**
 * Frame-local graph-native filter builder. Names are scoped to the filter's index
 * and name, so multiple instances do not collide. Sizes are relative to the FPP
 * scene dimensions, never the size left on the camera by a preceding blur pass.
 * Materials follow jME's Texture/NumSamples and DepthTexture/NumSamplesDepth
 * conventions. Use MultiSample.glsllib for sampling multisampled inputs.
 */
public final class FilterGraphContext {
    private final FrameGraph graph;
    private final FilterPostProcessor processor;
    private final String prefix;
    private final int width;
    private final int height;
    private final ResourceHandle<Texture2D> depth;

    FilterGraphContext(FrameGraph graph, FilterPostProcessor processor, String prefix,
        int width, int height, ResourceHandle<Texture2D> depth) {
        this.graph = graph;
        this.processor = processor;
        this.prefix = prefix;
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    /** @return shared graph for custom compute/draw passes; do not execute/close it */
    public FrameGraph getGraph() {
        return graph;
    }

    /**
     * @param local local pass/resource name
     * @return filter-scoped unique name
     */
    public String name(String local) {
        return prefix + "/" + Objects.requireNonNull(local);
    }

    /**
     * @return original scene-depth handle; context.get(handle) is null unless depth
     * was requested by an enabled/initialized filter through isRequiresDepthTexture
     */
    public ResourceHandle<Texture2D> getDepth() {
        return depth;
    }

    /**
     * Declares a pooled, single-sample color target without depth. The writer must
     * initialize it; draw() clears and fills it. Returned storage can be reused
     * after its last declared reader, so custom passes must declare every read.
     * <pre>{@code
     * // 先声明半分辨率临时颜色，再由当前效果写入，下一 Pass 读取。
     * ResourceHandle<RenderTarget> half = context.createColor("Half", .5f, Image.Format.RGBA16F);
     * context.draw("Downsample", copyMaterial, input, half);
     * }</pre>
     * @param local resource name
     * @param scale positive finite fraction of original scene dimensions
     * @param format color format supported by the renderer
     * @return frame-local image handle
     */
    public ResourceHandle<RenderTarget> createColor(String local, float scale, Image.Format format) {
        if (!Float.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Invalid color target scale");
        }
        double w = Math.ceil(width * (double) scale);
        double h = Math.ceil(height * (double) scale);
        if (w > Integer.MAX_VALUE || h > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Color target is too large");
        }
        return createColor(local, Math.max(1, (int) w), Math.max(1, (int) h), format);
    }

    /**
     * Creates an exact-size transient target, for example a 1x1 exposure meter.
     * Same ownership rules as the relative-size overload apply.
     * <pre>{@code
     * // 第一步：声明固定尺寸的测光结果，不分配视口大小的纹理。
     * ResourceHandle<RenderTarget> meter = context.createColor("Meter", 1, 1, Image.Format.RGBA16F);
     * // 第二步：context.draw("Measure", material, input, meter) 声明写入。
     * }</pre>
     * @param local scoped name
     * @param width positive pixel width
     * @param height positive pixel height
     * @param format supported color format
     * @return frame-local resource handle
     */
    public ResourceHandle<RenderTarget> createColor(String local, int width, int height, Image.Format format) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Color dimensions must be positive");
        }
        return graph.createResource(name(local), new ColorDefinition(width, height, format));
    }

    /**
     * Adds a fullscreen material draw and declares input/depth reads and output write.
     * Texture and sample-count parameters are assigned at execution time. If the
     * material declares DepthTexture, scene depth must have been requested.
     * @param local pass name, also visible in RenderDoc
     * @param material material loaded normally from j3md
     * @param input sampled color input
     * @param output distinct destination; in-place feedback is forbidden
     * @return declaration for additional dependencies if needed
     */
    public FrameGraph.PassBuilder draw(String local, Material material,
        ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> output) {
        return draw(local, material, input, output, java.util.Collections.emptyMap());
    }

    /**
     * Draws with additional single-sample color inputs, binding textures only at
     * execution and declaring all lifetimes. Extra parameters must be Texture2D.
     * <pre>{@code
     * // 第一步：先生成 bloom；第二步：声明合成时读取它，禁止手动缓存池中纹理。
     * context.draw("Composite", material, scene, output, Map.of("BloomTexture", bloom));
     * // 第三步：output 不能同时作为任何输入，避免 framebuffer feedback。
     * }</pre>
     * @param local pass name
     * @param material material owned by the caller
     * @param input primary input, optionally multisampled
     * @param output separate output
     * @param textures additional parameter-to-resource bindings
     * @return pass declaration
     */
    public FrameGraph.PassBuilder draw(String local, Material material,
        ResourceHandle<RenderTarget> input, ResourceHandle<RenderTarget> output,
        java.util.Map<String, ResourceHandle<RenderTarget>> textures) {
        Objects.requireNonNull(material);
        java.util.Map<String, ResourceHandle<RenderTarget>> bindings = new java.util.LinkedHashMap<>(textures);
        if (input == output || bindings.containsValue(output)) {
            throw new IllegalArgumentException("Use a separate output, not texture feedback");
        }
        FrameGraph.PassBuilder pass = graph.addPass(name(local), context -> {
            for (java.util.Map.Entry<String, ResourceHandle<RenderTarget>> entry : bindings.entrySet()) {
                material.setTexture(entry.getKey(), context.get(entry.getValue()).getColorTexture(0));
            }
            processor.drawGraphMaterial(material, context.get(input).getColorTexture(0),
                context.get(depth), context.get(output).getFrameBuffer());
        }).read(input).read(depth).write(output);
        for (ResourceHandle<RenderTarget> handle : bindings.values()) {
            pass.read(handle);
        }
        return pass;
    }

    private static final class ColorDefinition implements ResourceDefinition<RenderTarget> {
        private final int width;
        private final int height;
        private final RenderTargetDefinition target;

        ColorDefinition(int width, int height, Image.Format format) {
            this.width = width;
            this.height = height;
            target = new RenderTargetDefinition(1f, 1, null, format);
        }

        public Class<RenderTarget> getType() {
            return RenderTarget.class;
        }

        public RenderTarget create(Renderer renderer, int ignoredWidth, int ignoredHeight) {
            return target.create(renderer, width, height);
        }

        public void destroy(RenderTarget value) {
            target.destroy(value);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ColorDefinition)) {
                return false;
            }
            ColorDefinition definition = (ColorDefinition) other;
            return width == definition.width && height == definition.height && target.equals(definition.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(width, height, target);
        }
    }
}
