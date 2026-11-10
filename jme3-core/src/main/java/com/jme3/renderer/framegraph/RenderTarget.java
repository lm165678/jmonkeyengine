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

import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture2D;

/**
 * Graph-owned framebuffer and its owned attachment textures. Borrow inside a
 * declared pass; do not dispose or replace attachments. Pool contents are undefined
 * until the writer initializes them. Use getColorTexture to sample in a later pass.
 */
public final class RenderTarget {
    final FrameBuffer buffer;
    final Texture2D[] colors;
    final Texture2D depth;
    RenderTarget(FrameBuffer buffer, Texture2D[] colors, Texture2D depth) {
        this.buffer = buffer;
        this.colors = colors;
        this.depth = depth;
    }
    /** @return framebuffer to bind with Renderer.setFrameBuffer */
    public FrameBuffer getFrameBuffer() {
        return buffer;
    }
    /** @return number of color attachments */
    public int getColorCount() {
        return colors.length;
    }
    /** @param index color attachment index @return sampled color texture */
    public Texture2D getColorTexture(int index) {
        return colors[index];
    }
    /** @return sampled depth texture, or null if not requested */
    public Texture2D getDepthTexture() {
        return depth;
    }
}
