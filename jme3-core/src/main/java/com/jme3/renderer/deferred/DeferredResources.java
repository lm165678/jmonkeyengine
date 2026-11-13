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
package com.jme3.renderer.deferred;

import com.jme3.asset.AssetManager;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.util.Map;

/** Owns persistent light data/programs and borrows transient GBuffer targets from FrameGraph. */
final class DeferredResources {
    static final String ROOT = "Common/MatDefs/Light/Tiled/";
    private final AssetManager assets;
    private final Renderer renderer;
    FrameBuffer gbuffer;
    final Texture2D[] targets = new Texture2D[6];
    Texture2D lightTexture, tileTexture;
    BufferObject gpuIndices;
    final Mesh quad = new Quad(1, 1);
    private Shader cpuShader, gpuShader, computeShader;
    private int gpuRecords;
    private long modelVersion = -1;
    private boolean closed;

    DeferredResources(AssetManager assets, Renderer renderer) {
        this.assets = assets;
        this.renderer = renderer;
    }
    AssetManager getAssetManager() {
        return assets;
    }
    void borrowTargets(com.jme3.renderer.framegraph.RenderTarget target) {
        releaseTargets();
        gbuffer = target.getFrameBuffer();
        for (int i = 0; i < 5; i++) {
            targets[i] = target.getColorTexture(i);
        }
        targets[5] = target.getDepthTexture();
    }
    Texture2D storage(Texture2D old, int width, int height, Image.Format format, int bytes) {
        requireOpen();
        if (old != null && old.getImage().getWidth() == width
            && old.getImage().getHeight() == height) {
            old.getImage().getData(0).clear();
            return old;
        }
        if (old != null) {
            old.getImage().dispose();
        }
        Texture2D texture = new Texture2D(new Image(
            format, width, height, BufferUtils.createByteBuffer(bytes), ColorSpace.Linear));
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        return texture;
    }
    void ensureGpuIndices(int records) {
        requireOpen();
        if (gpuIndices == null || records != gpuRecords) {
            if (gpuIndices != null) {
                gpuIndices.dispose();
            }
            gpuIndices = com.jme3.renderer.framegraph.PackedBufferData.create(0);

            // std430 scalar indices use four bytes each; round allocation to 16 bytes.
            com.jme3.renderer.framegraph.PackedBufferData.set(gpuIndices, new int[((records + 3) / 4) * 4]);
            gpuRecords = records;
        }
    }
    Shader computeShader() {
        requireOpen();
        if (computeShader == null) {
            computeShader = new Shader();
            computeShader.addSource(Shader.ShaderType.Compute, ROOT + "Cull.comp",
                (String) assets.loadAsset(ROOT + "Cull.comp"), "", "GLSL430");
        }
        return computeShader;
    }
    Shader lightingShader(boolean gpu, ShadingModelRegistry models) {
        requireOpen();
        if (modelVersion != models.getVersion()) {
            deleteLightingShaders();
            modelVersion = models.getVersion();
        }
        Map<Integer, String> customModels = models.getSources();
        Shader shader = gpu ? gpuShader : cpuShader;
        if (shader == null) {
            String source = (String) assets.loadAsset(ROOT + "Lighting.frag");
            source = assembleLightingSource(source, customModels);
            shader = new Shader();
            String language = gpu ? "GLSL430" : "GLSL330";
            shader.addSource(Shader.ShaderType.Vertex, ROOT + "Fullscreen.vert",
                (String) assets.loadAsset(ROOT + "Fullscreen.vert"), "", language);
            shader.addSource(Shader.ShaderType.Fragment, ROOT + "Lighting.frag", source,
                gpu ? "#define GPU_TILES\n" : "", language);
            if (gpu) {
                gpuShader = shader;
            } else {
                cpuShader = shader;
            }
        }
        return shader;
    }
    /**
     * Generates only named function/dispatch insertion points, never edits main()
     * or embeds knowledge of built-in surface channels. Full-surface custom models
     * own their complete layout. The old per-light API uses a documented adapter.
     */
    static String assembleLightingSource(String template, Map<Integer, String> models) {
        StringBuilder functions = new StringBuilder();
        StringBuilder dispatch = new StringBuilder();
        for (Map.Entry<Integer, String> entry : models.entrySet()) {
            int id = entry.getKey();
            String source = entry.getValue();
            functions.append(source).append("\n");
            boolean fullSurface =
                java.util.regex.Pattern.compile("\\bvec4\\s+deferredSurfaceModel" + id + "\\s*\\(")
                    .matcher(source)
                    .find();
            String functionName =
                fullSurface ? "deferredSurfaceModel" + id : "shadeLegacyModel" + id;
            if (!fullSurface) {
                functions.append(legacyModelAdapter(id));
            }
            dispatch.append("    if (shadingId == ")
                .append(id)
                .append(") {\n")
                .append("        return ")
                .append(functionName)
                .append("();\n")
                .append("    }\n");
        }
        return template.replace("// CUSTOM_MODEL_FUNCTIONS", functions.toString())
            .replace("// CUSTOM_MODEL_DISPATCH", dispatch.toString());
    }

    private static String legacyModelAdapter(int id) {
        // This layout applies ONLY to the legacy deferredModelN per-light API.
        // Preferred deferredSurfaceModelN functions decode their own channels.
        return "vec4 shadeLegacyModel" + id + "() {\n"
            + "    vec4 record0 = getGBuffer0();\n"
            + "    vec4 record1 = getGBuffer1();\n"
            + "    vec4 record2 = getGBuffer2();\n"
            + "    vec4 record3 = getGBuffer3();\n"
            + "    vec3 position = getWorldPosition();\n"
            + "    vec3 viewDirection = getViewDirection(position);\n"
            + "    DeferredLightList lights = getLightList();\n"
            + "    vec3 result = record3.rgb * AmbientColor;\n"
            + "    for (int entry = 0; entry < lights.count; ++entry) {\n"
            + "        DeferredLight light = getLight(lights, entry, position);\n"
            + "        result += deferredModel" + id + "(record0, record1, record2, record3,\n"
            + "                viewDirection, light.direction, light.color) * light.attenuation;\n"
            + "    }\n"
            + "    return vec4(result, record3.a);\n"
            + "}\n";
    }

    void releaseTargets() {
        // Graph owns disposal/pooling; only clear these borrowed references.
        gbuffer = null;
        java.util.Arrays.fill(targets, null);
    }
    private void deleteLightingShaders() {
        if (cpuShader != null && cpuShader.getId() != -1) {
            cpuShader.dispose();
        }
        if (gpuShader != null && gpuShader.getId() != -1) {
            gpuShader.dispose();
        }
        cpuShader = gpuShader = null;
    }
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Deferred resources have been released");
        }
    }

    void cleanup() {
        if (closed) {
            return;
        }
        closed = true;
        releaseTargets();
        deleteLightingShaders();
        if (computeShader != null && computeShader.getId() != -1) {
            computeShader.dispose();
        }
        if (lightTexture != null) {
            lightTexture.getImage().dispose();
        }
        if (tileTexture != null) {
            tileTexture.getImage().dispose();
        }
        if (gpuIndices != null) {
            gpuIndices.dispose();
        }
        for (VertexBuffer buffer : quad.getBufferList()) {
            buffer.dispose();
        }
        lightTexture = tileTexture = null;
        gpuIndices = null;
        computeShader = null;
        gpuRecords = 0;
    }
}
