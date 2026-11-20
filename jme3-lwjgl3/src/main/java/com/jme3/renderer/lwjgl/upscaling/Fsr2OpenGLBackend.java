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
package com.jme3.renderer.lwjgl.upscaling;

import com.jme3.math.Vector2f;
import com.jme3.renderer.*;
import com.jme3.renderer.upscaling.TemporalUpscalingBackend;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import org.lwjgl.opengl.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** OpenGL adapter for AMD FSR2's FP32 compute pipeline. */
public final class Fsr2OpenGLBackend implements TemporalUpscalingBackend {
    private static final String ROOT = "/Common/FidelityFX/FSR2/";
    private static final String[] PASSES = {"prepare_reactive", "compute_luminance_pyramid", "reconstruct_previous_depth",
            "depth_clip", "lock", "accumulate", "rcas"};
    private final Map<String, Surface> surfaces = new LinkedHashMap<>();
    private final Map<String, Surface> bindings = new HashMap<>();
    private final Map<String, Integer> programs = new LinkedHashMap<>();
    private final Map<String, List<Binding>> uniformBindings = new HashMap<>();
    private final int[] buffers = new int[3];
    private final int[] samplers = new int[2];
    private int rw, rh, ow, oh, frame, framebuffer;
    private Texture2D result;
    private Renderer lastRenderer;

    private static final class Surface {
        int texture, format, levels;
        Surface(int texture, int format, int levels) {
            this.texture = texture;
            this.format = format;
            this.levels = levels;
        }
    }

    private static final class Binding {
        String name;
        int unit;
        boolean image;
        boolean linear;
    }

    private void inspectBindings(String name, int program) {
        GL20.glUseProgram(program);
        String[] blocks = {"cbFSR2_t", "cbSPD_t", "cbRCAS_t"};
        for (int i = 0; i < blocks.length; i++) {
            int block = GL31.glGetUniformBlockIndex(program, blocks[i]);
            if (block != GL31.GL_INVALID_INDEX) {
                GL31.glUniformBlockBinding(program, block, i);
            }
        }
        List<Binding> layout = new ArrayList<>();
        IntBuffer size = BufferUtils.createIntBuffer(1);
        IntBuffer type = BufferUtils.createIntBuffer(1);
        int textures = 0;
        int images = 0;
        for (int i = 0; i < GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS); i++) {
            String uniform = GL20.glGetActiveUniform(program, i, 256, size, type);
            int location = GL20.glGetUniformLocation(program, uniform);
            if (location < 0) {
                continue;
            }
            Binding binding = new Binding();
            binding.linear = uniform.endsWith("_linear");
            binding.name = binding.linear ? uniform.substring(0, uniform.length() - 7) : uniform;
            binding.image = type.get(0) == GL42.GL_IMAGE_2D || type.get(0) == GL42.GL_UNSIGNED_INT_IMAGE_2D;
            if (!binding.image && type.get(0) != GL20.GL_SAMPLER_2D
                    && type.get(0) != GL30.GL_UNSIGNED_INT_SAMPLER_2D) {
                throw new IllegalStateException("Unexpected FSR2 uniform " + uniform);
            }
            binding.unit = binding.image ? images++ : textures++;
            GL20.glUniform1i(location, binding.unit);
            layout.add(binding);
        }
        if (textures > GL11.glGetInteger(GL43.GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS)
                || images > GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)) {
            throw new UnsupportedOperationException("Insufficient FSR2 texture/image binding slots");
        }
        uniformBindings.put(name, layout);
    }

    @Override
    public String getAlgorithmId() {
        return "FSR2";
    }

    @Override
    public boolean supports(Renderer renderer) {
        return renderer instanceof com.jme3.renderer.opengl.GLRenderer
                && renderer.getCaps().contains(Caps.OpenGL43);
    }

    private String expand(String file, Set<String> stack) throws IOException {
        if (file.equals("ffx_fsr2_force16_begin.h") || file.equals("ffx_fsr2_force16_end.h")) {
            return "";
        }
        if (!stack.add(file)) {
            return "";
        }
        String source;
        try (InputStream stream = getClass().getResourceAsStream(ROOT + file)) {
            if (stream == null) {
                throw new FileNotFoundException(file);
            }
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher include = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\".*$").matcher(source);
        StringBuffer expanded = new StringBuffer();
        while (include.find()) {
            include.appendReplacement(expanded, Matcher.quoteReplacement(expand(include.group(1), stack)));
        }
        include.appendTail(expanded);
        stack.remove(file);
        return expanded.toString();
    }

    private int compile(String pass) {
        try {
            String source = expand("ffx_fsr2_" + pass + "_pass.glsl", new HashSet<>());
            source = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
            source = source.replaceAll("(?m)^\\s*#version.*$", "")
                    .replaceAll("(?m)^\\s*#extension GL_(GOOGLE_include_directive|EXT_samplerless_texture_functions|EXT_shader_image_load_formatted).*$", "");
            Matcher layouts = Pattern.compile("layout\\s*\\(([^)]*)\\)").matcher(source);
            StringBuffer rewritten = new StringBuffer();
            while (layouts.find()) {
                List<String> parts = new ArrayList<>();
                for (String part : layouts.group(1).split(",")) {
                    if (!part.trim().startsWith("set") && !part.trim().startsWith("binding")) {
                        parts.add(part.trim());
                    }
                }
                layouts.appendReplacement(rewritten, Matcher.quoteReplacement(parts.isEmpty() ? "" : "layout(" + String.join(",", parts) + ")"));
            }
            layouts.appendTail(rewritten);
            source = rewritten.toString().replaceAll("(?m)^\\s*uniform sampler s_\\w+;", "");
            source = source.replaceAll("(uniform\\s+u?)texture2D(\\s+)(\\w+)(\\s*;)", "$1sampler2D$2$3$4");
            Matcher declarations = Pattern.compile("uniform\\s+(u?sampler2D)\\s+(\\w+)\\s*;").matcher(source);
            StringBuffer samplersSource = new StringBuffer();
            while (declarations.find()) {
                declarations.appendReplacement(samplersSource, Matcher.quoteReplacement(declarations.group()
                        + "\nuniform " + declarations.group(1) + " " + declarations.group(2) + "_linear;"));
            }
            declarations.appendTail(samplersSource);
            source = samplersSource.toString()
                    .replaceAll("sampler2D\\((\\w+),\\s*s_LinearClamp\\)", "$1_linear")
                    .replaceAll("sampler2D\\((\\w+),\\s*s_PointClamp\\)", "$1")
                    .replaceAll("(?m)^(\\s*)(writeonly uniform image2D\\s+rw_upscaled_output;)", "$1layout(rgba16f) $2");
            source = source.replace("layout(rgba16)", "layout(rgba16f)")
                    .replaceAll("layout\\(r16f\\)(\\s*writeonly uniform image2D\\s+rw_dilatedDepth)", "layout(r32f)$1");
            source = source.replace("FfxUInt32 bitfieldInsert(", "FfxUInt32 jmeInsertBits(")
                    .replace("bitfieldExtract(", "jmeExtractBits(")
                    .replace("return jmeExtractBits(src, FfxInt32(off), FfxInt32(bits));",
                            "return bitfieldExtract(src, FfxInt32(off), FfxInt32(bits));");
            source = "#version 430\n#define FFX_GPU 1\n#define FFX_GLSL 1\n#define FFX_HALF 0\n"
                    + "#define FFX_FSR2_OPTION_LOW_RESOLUTION_MOTION_VECTORS 1\n"
                    + "#define FFX_FSR2_OPTION_HDR_COLOR_INPUT 1\n#define FFX_FSR2_OPTION_APPLY_SHARPENING 1\n"
                    + "#define FFX_FSR2_OPTION_INVERTED_DEPTH 0\n#define FFX_FSR2_OPTION_JITTERED_MOTION_VECTORS 0\n" + source;
            int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
            GL20.glShaderSource(shader, source);
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                String log = GL20.glGetShaderInfoLog(shader);
                GL20.glDeleteShader(shader);
                throw new IllegalStateException("FSR2 " + pass + ": " + log);
            }
            int program = GL20.glCreateProgram();
            GL20.glAttachShader(program, shader);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(shader);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                String log = GL20.glGetProgramInfoLog(program);
                GL20.glDeleteProgram(program);
                throw new IllegalStateException("FSR2 link " + pass + ": " + log);
            }
            return program;
        } catch (IOException exception) {
            throw new IllegalStateException("Missing FSR2 shader source", exception);
        }
    }

    private Surface surface(String name, int width, int height, int format, int levels) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, format, Math.max(1, width), Math.max(1, height));
        Surface surface = new Surface(id, format, levels);
        surfaces.put(name, surface);
        clear(surface, 0, width, height);
        return surface;
    }

    private void clear(Surface surface, float value, int width, int height) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        for (int level = 0; level < surface.levels; level++) {
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, surface.texture, level);
            if (surface.format == GL30.GL_R32UI) {
                GL30.glClearBufferuiv(GL11.GL_COLOR, 0, new int[]{Float.floatToRawIntBits(value), 0, 0, 0});
            } else {
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{value, value, value, value});
            }
        }
    }

    private void allocate(int width, int height, int outputWidth, int outputHeight) {
        close();
        rw = width;
        rh = height;
        ow = outputWidth;
        oh = outputHeight;
        framebuffer = GL30.glGenFramebuffers();
        for (String pass : PASSES) {
            int program = compile(pass);
            programs.put(pass, program);
            inspectBindings(pass, program);
        }
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = GL15.glGenBuffers();
        }
        for (int i = 0; i < 2; i++) {
            samplers[i] = GL33.glGenSamplers();
            GL33.glSamplerParameteri(samplers[i], GL11.GL_TEXTURE_MIN_FILTER, i == 0 ? GL11.GL_NEAREST : GL11.GL_LINEAR_MIPMAP_NEAREST);
            GL33.glSamplerParameteri(samplers[i], GL11.GL_TEXTURE_MAG_FILTER, i == 0 ? GL11.GL_NEAREST : GL11.GL_LINEAR);
            GL33.glSamplerParameteri(samplers[i], GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(samplers[i], GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        surface("reconstructed_previous_nearest_depth", rw, rh, GL30.GL_R32UI, 1);
        surface("prepared_input_color", rw, rh, GL30.GL_RGBA16F, 1);
        surface("dilatedDepth", rw, rh, GL30.GL_R32F, 1);
        surface("lock_input_luma", rw, rh, GL30.GL_R16F, 1);
        surface("dilated_reactive_masks", rw, rh, GL30.GL_RG8, 1);
        surface("new_locks", ow, oh, GL30.GL_R8, 1);
        for (int i = 0; i < 2; i++) {
            surface("motion" + i, rw, rh, GL30.GL_RG16F, 1);
            surface("lock" + i, ow, oh, GL30.GL_RG16F, 1);
            surface("internal" + i, ow, oh, GL30.GL_RGBA16F, 1);
            surface("luma" + i, ow, oh, GL11.GL_RGBA8, 1);
        }
        int mips = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(rw / 2, rh / 2)));
        surface("imgMips", Math.max(1, rw / 2), Math.max(1, rh / 2), GL30.GL_R16F, mips);
        surface("spd_global_atomic", 1, 1, GL30.GL_R32UI, 1);
        surface("auto_exposure", 1, 1, GL30.GL_RG32F, 1);
        surface("input_exposure", 1, 1, GL30.GL_RG32F, 1);
        clear(surfaces.get("input_exposure"), 1, 1, 1);
        surface("zero", rw, rh, GL30.GL_R8, 1);
        surface("resolved_reactive", rw, rh, GL30.GL_R8, 1);
        surface("resolved_motion", rw, rh, GL30.GL_RG16F, 1);
        Surface output = surface("upscaled_output", ow, oh, GL30.GL_RGBA16F, 1);
        Image image = new Image(Image.Format.RGBA16F, ow, oh, (ByteBuffer) null);
        image.setId(output.texture);
        image.clearUpdateNeeded();
        result = new Texture2D(image);
        result.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        result.setMagFilter(Texture.MagFilter.Bilinear);
        clear(surfaces.get("reconstructed_previous_nearest_depth"), 1, rw, rh);
        createLuts();
    }

    private void createLuts() {
        Surface lut = surface("lanczos_lut", 128, 1, GL31.GL_R16_SNORM, 1);
        ShortBuffer weights = BufferUtils.createShortBuffer(128);
        for (int i = 0; i < 128; i++) {
            double x = 2.0 * i / 127;
            double weight = x == 0 ? 1 : Math.sin(Math.PI * x) * Math.sin(Math.PI * x / 2) / (Math.PI * Math.PI * x * x / 2);
            weights.put((short) Math.round(weight * 32767));
        }
        weights.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lut.texture);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 128, 1, GL11.GL_RED, GL11.GL_SHORT, weights);
        // The maximum-bias table is supplied as a signed-normalized AMD resource.
        Surface bias = surface("upsample_maximum_bias_lut", 16, 16, GL31.GL_R16_SNORM, 1);
        try (InputStream stream = getClass().getResourceAsStream(ROOT + "maximum_bias.bin")) {
            if (stream == null) {
                throw new FileNotFoundException("maximum_bias.bin");
            }
            byte[] bytes = stream.readAllBytes();
            ByteBuffer data = BufferUtils.createByteBuffer(bytes.length);
            data.put(bytes).flip();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bias.texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 16, 16, GL11.GL_RED, GL11.GL_SHORT, data);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void constants(int index, ByteBuffer data) {
        data.flip();
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffers[index]);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, index, buffers[index]);
    }

    private void map(String name, Surface surface) {
        bindings.put("r_" + name, surface);
        bindings.put("rw_" + name, surface);
    }

    private void bindExternal(Renderer renderer, String name, Texture2D texture) {
        try {
            renderer.setTexture(0, texture);
        } catch (com.jme3.renderer.TextureUnitException exception) {
            throw new IllegalStateException("Cannot upload FSR2 input", exception);
        }
        map(name, new Surface(texture.getImage().getId(), 0, 1));
    }

    private void dispatch(String name, int x, int y) {
        int program = programs.get(name);
        GL20.glUseProgram(program);
        for (Binding binding : uniformBindings.get(name)) {
            Surface surface = bindings.get(binding.name);
            if (surface == null) {
                throw new IllegalStateException("Unbound FSR2 resource " + name + "/" + binding.name);
            }
            if (!binding.image) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + binding.unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, surface.texture);
                GL33.glBindSampler(binding.unit, samplers[binding.linear ? 1 : 0]);
            } else {
                int mip = binding.name.equals("rw_img_mip_shading_change") ? 4 : binding.name.equals("rw_img_mip_5") ? 5 : 0;
                GL42.glBindImageTexture(binding.unit, surface.texture, Math.min(mip, surface.levels - 1),
                        false, 0, GL15.GL_READ_WRITE, surface.format);
            }
        }
        GL43.glDispatchCompute(x, y, 1);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
    }

    @Override
    public Texture2D reconstruct(RenderManager manager, Texture2D color, Texture2D depth,
            Texture2D motion, Texture2D reactive, Texture2D composition, Camera camera,
            Vector2f jitterPixels, float deltaSeconds, int outputWidth, int outputHeight,
            float sharpness, boolean reset) {
        Renderer renderer = manager.getRenderer();
        lastRenderer = renderer;
        int width = color.getImage().getWidth();
        int height = color.getImage().getHeight();
        if (width < 1 || height < 1 || outputWidth < width || outputHeight < height
                || color.getImage().getMultiSamples() > 1 || camera.isParallelProjection()) {
            throw new IllegalArgumentException("FSR2 requires perspective, single-sample inputs and output >= render size");
        }
        for (Texture2D input : new Texture2D[]{depth, motion, reactive, composition}) {
            if (input != null && (input.getImage().getWidth() != width || input.getImage().getHeight() != height
                    || input.getImage().getMultiSamples() > 1)) {
                throw new IllegalArgumentException("FSR2 depth, motion and masks must match render dimensions");
            }
        }
        int oldFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            renderer.invalidateState();
            if (rw != width || rh != height || ow != outputWidth || oh != outputHeight || programs.isEmpty()) {
                allocate(width, height, outputWidth, outputHeight);
                reset = true;
            }
            if (reset) {
                frame = 0;
                for (Map.Entry<String, Surface> item : surfaces.entrySet()) {
                    if (item.getKey().startsWith("lock") || item.getKey().startsWith("internal") || item.getKey().startsWith("luma")) {
                        clear(item.getValue(), 0, ow, oh);
                    }
                }
                clear(surfaces.get("imgMips"), 0, rw / 2, rh / 2);
                clear(surfaces.get("new_locks"), 0, ow, oh);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D, surfaces.get("auto_exposure").texture, 0);
                GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[]{-1, 1e8f, 0, 0});
                clear(surfaces.get("reconstructed_previous_nearest_depth"), 1, rw, rh);
            }
            bindings.clear();
            for (Map.Entry<String, Surface> item : surfaces.entrySet()) {
                map(item.getKey(), item.getValue());
            }
            int next = frame & 1;
            map("lock_status", surfaces.get("lock" + (1 - next)));
            bindings.put("rw_lock_status", surfaces.get("lock" + next));
            map("internal_upscaled_color", surfaces.get("internal" + (1 - next)));
            bindings.put("rw_internal_upscaled_color", surfaces.get("internal" + next));
            map("rcas_input", surfaces.get("internal" + next));
            map("luma_history", surfaces.get("luma" + (1 - next)));
            bindings.put("rw_luma_history", surfaces.get("luma" + next));
            map("dilated_motion_vectors", surfaces.get("motion" + next));
            map("previous_dilated_motion_vectors", surfaces.get("motion" + (1 - next)));
            map("reactive_mask", surfaces.get("resolved_reactive"));
            map("user_reactive", surfaces.get("zero"));
            bindings.put("rw_reactive_mask", surfaces.get("resolved_reactive"));
            bindings.put("rw_resolved_motion", surfaces.get("resolved_motion"));
            map("input_motion_vectors", surfaces.get("resolved_motion"));
            map("transparency_and_composition_mask", surfaces.get("zero"));
            bindings.put("rw_img_mip_shading_change", surfaces.get("imgMips"));
            bindings.put("rw_img_mip_5", surfaces.get("imgMips"));
            bindExternal(renderer, "input_color_jittered", color);
            bindExternal(renderer, "input_depth", depth);
            bindExternal(renderer, "user_motion", motion);
            if (reactive != null) {
                bindExternal(renderer, "user_reactive", reactive);
            }
            if (composition != null) {
                bindExternal(renderer, "transparency_and_composition_mask", composition);
            }
            ByteBuffer cb = BufferUtils.createByteBuffer(128);
            for (int value : new int[]{rw, rh, rw, rh, ow, oh, rw, rh, Math.max(1, rw / 32), Math.max(1, rh / 32), 4, frame}) {
                cb.putInt(value);
            }
            float near = camera.getFrustumNear();
            float far = camera.getFrustumFar();
            cb.putFloat(far / (far - near)).putFloat(-far * near / (far - near));
            cb.putFloat(1 / camera.getProjectionMatrix().m00).putFloat(1 / camera.getProjectionMatrix().m11);
            cb.putFloat(jitterPixels.x).putFloat(jitterPixels.y);
            cb.putFloat(-1).putFloat(-1);
            cb.putFloat((float) rw / ow).putFloat((float) rh / oh);
            cb.putFloat(0).putFloat(0);
            cb.putFloat(1).putFloat(1).putFloat(1 / camera.getProjectionMatrix().m11);
            cb.putFloat(Math.max(1, (int) (8.0 * ow * ow / ((double) rw * rw))));
            cb.putFloat(Math.max(0, Math.min(1, deltaSeconds))).putFloat(1).putFloat(1).putFloat(0);
            constants(0, cb);
            int gx = (rw + 63) / 64;
            int gy = (rh + 63) / 64;
            ByteBuffer spd = BufferUtils.createByteBuffer(32);
            spd.putInt(Math.min(12, 31 - Integer.numberOfLeadingZeros(Math.max(rw, rh))))
                    .putInt(gx * gy).putInt(0).putInt(0).putInt(rw).putInt(rh).putLong(0);
            constants(1, spd);
            ByteBuffer rcas = BufferUtils.createByteBuffer(16);
            rcas.putFloat((float) Math.pow(2, -(2 - 2 * sharpness))).putInt(0).putInt(0).putInt(0);
            constants(2, rcas);
            clear(surfaces.get("spd_global_atomic"), 0, 1, 1);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            for (String pass : PASSES) {
                renderer.pushDebugGroup("FSR2/" + pass);
                try {
                    int x = pass.equals("compute_luminance_pyramid") ? gx : pass.equals("rcas") ? (ow + 15) / 16
                            : pass.equals("accumulate") ? (ow + 7) / 8 : (rw + 7) / 8;
                    int y = pass.equals("compute_luminance_pyramid") ? gy : pass.equals("rcas") ? (oh + 15) / 16
                            : pass.equals("accumulate") ? (oh + 7) / 8 : (rh + 7) / 8;
                    dispatch(pass, x, y);
                } finally {
                    renderer.popDebugGroup();
                }
            }
            frame++;
            return result;
        } finally {
            for (int i = 0; i < 32; i++) {
                GL33.glBindSampler(i, 0);
            }
            GL20.glUseProgram(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldFramebuffer);
            renderer.invalidateState();
        }
    }

    @Override
    public void close() {
        for (int program : programs.values()) {
            GL20.glDeleteProgram(program);
        }
        programs.clear();
        uniformBindings.clear();
        for (Surface surface : surfaces.values()) {
            GL11.glDeleteTextures(surface.texture);
        }
        surfaces.clear();
        bindings.clear();
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != 0) {
                GL15.glDeleteBuffers(buffers[i]);
                buffers[i] = 0;
            }
        }
        for (int i = 0; i < samplers.length; i++) {
            if (samplers[i] != 0) {
                GL33.glDeleteSamplers(samplers[i]);
                samplers[i] = 0;
            }
        }
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
            framebuffer = 0;
        }
        if (result != null) {
            result.getImage().resetObject();
            result = null;
        }
        if (lastRenderer != null) {
            lastRenderer.invalidateState();
        }
    }
}
