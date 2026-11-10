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
package com.jme3.renderer.framegraph;

import com.jme3.renderer.Renderer;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable viewport-relative framebuffer allocation definition, with owned color
 * and optional depth textures. Example:
 * <pre>{@code
 * ResourceHandle<RenderTarget> hdr = graph.createResource("hdr",
 *     new RenderTargetDefinition(1f, 1, Image.Format.Depth24, Image.Format.RGBA16F));
 * // Half-resolution color-only target:
 * ResourceHandle<RenderTarget> blur = graph.createResource("blur",
 *     new RenderTargetDefinition(.5f, 1, null, Image.Format.RGBA16F));
 * }</pre>
 * Sizes round up to at least one pixel. Equal definitions can share allocations
 * at non-overlapping lifetimes. Textures use nearest/clamp sampling; configure a
 * distinct custom ResourceDefinition if different sampling is required. Hardware
 * format/MRT/sample support remains the pipeline's responsibility.
 */
public final class RenderTargetDefinition implements ResourceDefinition<RenderTarget> {
    private final float scale;
    private final int samples;
    private final Image.Format depth;
    private final Image.Format[] colors;
    /**
     * @param scale positive finite fraction/multiple of viewport dimensions
     * @param samples positive sample count; ordinary sampled targets use 1
     * @param depth optional depth format
     * @param colors ordered color formats (at least one color or depth is required)
     */
    public RenderTargetDefinition(
        float scale, int samples, Image.Format depth, Image.Format... colors) {
        if (!Float.isFinite(scale) || scale <= 0 || samples < 1) {
            throw new IllegalArgumentException("Invalid target dimensions/samples");
        }
        Objects.requireNonNull(colors);
        this.colors = colors.clone();
        if (depth != null && !depth.isDepthFormat()) {
            throw new IllegalArgumentException("Expected depth format");
        }
        if (colors.length == 0 && depth == null) {
            throw new IllegalArgumentException("Target has no attachments");
        }
        for (Image.Format color : this.colors) {
            if (Objects.requireNonNull(color).isDepthFormat()) {
                throw new IllegalArgumentException("Expected color format");
            }
        }
        this.scale = scale;
        this.samples = samples;
        this.depth = depth;
    }
    @Override
    public Class<RenderTarget> getType() {
        return RenderTarget.class;
    }
    private int extent(int size) {
        double value = Math.ceil(size * (double) scale);
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Target too large");
        }
        return Math.max(1, (int) value);
    }
    private Texture2D texture(int w, int h, Image.Format format) {
        Texture2D result = new Texture2D(w, h, samples, format);
        result.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        result.setMagFilter(Texture.MagFilter.Nearest);
        return result;
    }
    @Override
    public RenderTarget create(Renderer renderer, int width, int height) {
        int w = extent(width), h = extent(height);
        FrameBuffer buffer = new FrameBuffer(w, h, samples);
        Texture2D[] attachments = new Texture2D[colors.length];
        Texture2D depthTexture = null;
        try {
            for (int i = 0; i < colors.length; i++) {
                attachments[i] = texture(w, h, colors[i]);
                buffer.addColorTexture(attachments[i]);
            }
            if (depth != null) {
                depthTexture = texture(w, h, depth);
                buffer.setDepthTexture(depthTexture);
            }
            if (colors.length > 1) {
                buffer.setMultiTarget(true);
            }
            return new RenderTarget(buffer, attachments, depthTexture);
        } catch (RuntimeException e) {
            buffer.dispose();
            for (Texture2D t : attachments) {
                if (t != null) {
                    t.getImage().dispose();
                }
            }
            if (depthTexture != null) {
                depthTexture.getImage().dispose();
            }
            throw e;
        }
    }
    @Override
    public void destroy(RenderTarget target) {
        target.buffer.dispose();
        for (Texture2D texture : target.colors) {
            texture.getImage().dispose();
        }
        if (target.depth != null) {
            target.depth.getImage().dispose();
        }
    }
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RenderTargetDefinition)) {
            return false;
        }
        RenderTargetDefinition d = (RenderTargetDefinition) other;
        return scale == d.scale && samples == d.samples && depth == d.depth
            && Arrays.equals(colors, d.colors);
    }
    @Override
    public int hashCode() {
        return Objects.hash(scale, samples, depth, Arrays.hashCode(colors));
    }
}
