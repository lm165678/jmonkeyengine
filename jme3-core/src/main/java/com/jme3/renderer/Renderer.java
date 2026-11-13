/*
 * Copyright (c) 2009-2024 jMonkeyEngine
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
package com.jme3.renderer;

import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.opengl.GLFence;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.Shader;
import com.jme3.shader.Shader.ShaderSource;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Image.Format;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureImage;
import com.jme3.util.NativeObject;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Responsible for taking rendering commands and
 * executing them on the underlying video hardware.
 *
 * @author Kirill Vainer
 */
public interface Renderer {
    /**
     * Begins a named GPU capture group. Unsupported backends safely do nothing.
     * FrameGraph calls this automatically for each pass; pair manual calls in
     * try/finally with popDebugGroup, on the render thread.
     * <pre>{@code
     * // FG 已自动添加标记；手工渲染时也可这样标记自己的绘制。
     * renderer.pushDebugGroup("Custom/Draw");
     * try {
     *     renderManager.renderGeometry(geometry);
     * } finally {
     *     renderer.popDebugGroup();
     * }
     * }</pre>
     * @param name capture label
     */
    default void pushDebugGroup(String name) {
    }

    /** Ends the current capture group; no-op on unsupported backends. */
    default void popDebugGroup() {
    }


    /**
     * Detects available capabilities of the GPU.
     *
     * Must be called prior to any other Renderer methods.
     */
    public void initialize();

    /**
     * Gets the capabilities of the renderer.
     *
     * @return The capabilities of the renderer.
     */
    public EnumSet<Caps> getCaps();

    /**
     * Gets the limits of the renderer.
     *
     * @return The limits of the renderer.
     */
    public EnumMap<Limits, Integer> getLimits();

    /**
     * Copies the render statistics.
     *
     * <p>The statistics allow tracking of how data
     * per frame, such as number of objects rendered, number of triangles, etc.
     * These are updated when the Renderer's methods are used, make sure
     * to call {@link Statistics#clearFrame() } at the appropriate time
     * to get accurate info per frame.
     *
     * @return a new instance
     */
    public Statistics getStatistics();

    /**
     * Invalidates the cached rendering state. Should be called after
     * the GL state was changed manually or through an external library.
     * <p>
     * The OpenGL renderer also immediately restores the states managed by
     * {@link RenderState} to OpenGL defaults, selects texture unit zero, and
     * binds the platform's default framebuffer for reading and drawing with
     * its configured sRGB write state. Previous external framebuffer bindings
     * are not preserved. Texture bindings are forgotten and rebound on use.
     * A main-framebuffer override remains configured, but is not selected or
     * allocated by this reset; select the intended target with
     * {@link #setFrameBuffer(FrameBuffer)} before resuming rendering.
     * <p>
     * Call this method on the rendering thread with the renderer's graphics
     * context current.
     */
    public void invalidateState();

    /**
     * Clears certain channels of the currently bound framebuffer.
     *
     * @param color True if to clear colors (RGBA)
     * @param depth True if to clear depth/z
     * @param stencil True if to clear stencil buffer (if available, otherwise
     * ignored)
     */
    public void clearBuffers(boolean color, boolean depth, boolean stencil);

    /**
     * Sets the background (aka clear) color.
     *
     * @param color The background color to set
     */
    public void setBackgroundColor(ColorRGBA color);

    /**
     * Applies the given {@link RenderState}, making the necessary
     * GL calls so that the state is applied.
     *
     * @param state the RenderState to apply
     */
    public void applyRenderState(RenderState state);

    /**
     * Sets the range of the depth values for objects. All rendered
     * objects will have their depth clamped to this range.
     *
     * @param start The range start
     * @param end The range end
     */
    public void setDepthRange(float start, float end);

    /**
     * Called when a new frame has been rendered.
     *
     * Currently, this will simply delete any OpenGL objects from the GPU
     * which have been garbage collected by the GC.
     */
    public void postFrame();

    /**
     * Sets the viewport location and resolution on the screen.
     *
     * @param x The x coordinate of the viewport
     * @param y The y coordinate of the viewport
     * @param width Width of the viewport
     * @param height Height of the viewport
     */
    public void setViewPort(int x, int y, int width, int height);

    /**
     * Specifies a clipping rectangle.
     * For all future rendering commands, no pixels will be allowed
     * to be rendered outside of the clip rectangle.
     *
     * @param x The x coordinate of the clip rect
     * @param y The y coordinate of the clip rect
     * @param width Width of the clip rect
     * @param height Height of the clip rect
     */
    public void setClipRect(int x, int y, int width, int height);

    /**
     * Clears the clipping rectangle set with
     * {@link #setClipRect(int, int, int, int) }.
     */
    public void clearClipRect();

    /**
     * Sets the shader to use for rendering.
     * If the shader has not been uploaded yet, it is compiled
     * and linked. If it has been uploaded, then the
     * uniform data is updated and the shader is set.
     *
     * @param shader The shader to use for rendering.
     */
    public void setShader(Shader shader);

    /**
     * Returns a bindless handle for an uploaded texture. Requires BindlessTextures.
     * Handle extraction freezes texture/sampler parameters on OpenGL: do not change
     * filters/wrap afterward. Call only on the renderer thread.
     * <pre>{@code
     * // 第一步：完成纹理采样参数设置，再申请句柄。
     * long handle = renderer.getTextureHandle(texture);
     * // 第二步：驻留后才能把句柄写入 GPU 材质表供 shader 使用。
     * renderer.setTextureHandleResident(handle, true);
     * // 第三步：移除所有 GPU 使用者后取消驻留，再释放纹理。
     * renderer.setTextureHandleResident(handle, false);
     * // 不支持时保留 GPU Scene，改用常规纹理绑定路径。
     * }</pre>
     * @param texture immutable-sampler texture
     * @return opaque 64-bit handle, not a texture ID
     */
    default long getTextureHandle(Texture texture) {
        throw new UnsupportedOperationException("Bindless textures unavailable");
    }

    /** @param handle borrowed handle @param resident residency requested on render thread */
    default void setTextureHandleResident(long handle, boolean resident) {
        throw new UnsupportedOperationException("Bindless textures unavailable");
    }

    /**
     * Draws an indexed mesh from a GPU-produced DrawElementsIndirectCommand.
     * Requires OpenGL 4.3 in the initial backend. The active shader/material and
     * render state must already be bound. No command readback occurs.
     * <pre>{@code
     * // 第一步：Compute 写入五个 uint：count, instanceCount, firstIndex, baseVertex, baseInstance。
     * renderer.dispatchCompute(groups, 1, 1);
     * // 第二步：命令/SSBO 写入对间接绘制和顶点读取可见。
     * renderer.memoryBarrier(0x2040);
     * // 第三步：材质/Shader 已绑定后绘制；offset 以字节为单位，必须四字节对齐。
     * renderer.renderMeshIndirect(mesh, 0, commands, 0);
     * // 第四步：停止使用后 dispose commands；常规 renderMesh 不受影响。
     * }</pre>
     * @param mesh indexed triangle mesh
     * @param lod index-buffer LOD
     * @param commands GPU command storage
     * @param byteOffset command offset
     */
    default void renderMeshIndirect(com.jme3.scene.Mesh mesh, int lod,
            BufferObject commands, long byteOffset) {
        throw new UnsupportedOperationException("Indirect rendering unavailable");
    }

    /**
     * Indirect indexed draw with GPU-produced instance attributes. The attributes
     * use the material's existing instancing contract; no shading logic is replaced.
     * @param mesh source mesh
     * @param lod index-buffer LOD
     * @param commands GPU command buffer
     * @param byteOffset byte offset
     * @param instanceData borrowed vertex attribute views of GPU storage
     */
    default void renderMeshIndirect(com.jme3.scene.Mesh mesh, int lod,
            BufferObject commands, long byteOffset, com.jme3.scene.VertexBuffer[] instanceData) {
        if (instanceData != null) {
            throw new UnsupportedOperationException("Indirect instance attributes unavailable");
        }
        renderMeshIndirect(mesh, lod, commands, byteOffset);
    }


    /** Dispatches the current compute shader and makes SSBO writes visible. */
    default void dispatchCompute(int groupsX, int groupsY, int groupsZ) {
        throw new UnsupportedOperationException("Compute shaders are unavailable");
    }

    /**
     * Publishes incoherent shader writes for subsequent GPU accesses. Bits use the
     * OpenGL memory-barrier mask; -1 means all barrier bits. Unsupported backends
     * must fail rather than silently omit synchronization. This is not a CPU fence.
     * <pre>{@code
     * // 通常由 FG 根据 ResourceAccess 自动调用，应用无需手动设置。
     * // 低层代码在 FG 之外直接写入 GPU 存储时，可保守同步后续所有访问：
     * renderer.memoryBarrier(-1);
     * }</pre>
     * @param barriers OpenGL barrier bits, zero for no operation
     */
    default void memoryBarrier(int barriers) {
        if (barriers != 0) {
            throw new UnsupportedOperationException("GPU memory barriers are unavailable");
        }
    }

    /** Number of samples in the currently bound framebuffer (one if unknown). */
    /** @return borrowed currently bound framebuffer, null for default; never dispose the result */
    default com.jme3.texture.FrameBuffer getCurrentFrameBuffer() {
        throw new UnsupportedOperationException("Framebuffer inspection unavailable");
    }

    default int getCurrentFrameBufferSamples() { return 1; }

    /**
     * Deletes a shader. This method also deletes
     * the attached shader sources.
     *
     * @param shader Shader to delete.
     * @see #deleteShaderSource(com.jme3.shader.Shader.ShaderSource)
     */
    public void deleteShader(Shader shader);

    /**
     * Deletes the provided shader source.
     *
     * @param source The ShaderSource to delete.
     */
    public void deleteShaderSource(ShaderSource source);

    /**
     * Copies contents from src to dst, scaling if necessary.
     * set copyDepth to false to only copy the color buffers.
     *
     * @param src the source FrameBuffer (unaffected)
     * @param dst the destination FrameBuffer (modified)
     * @param copyDepth true&rarr;copy depth info, false&rarr;don't copy it
     * @deprecated  Use {@link Renderer#copyFrameBuffer(com.jme3.texture.FrameBuffer,
     *     com.jme3.texture.FrameBuffer, boolean, boolean)}.
     */
    @Deprecated
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyDepth);

    /**
     * Copies contents from src to dst, scaling if necessary.
     *
     * @param src the source FrameBuffer (unaffected)
     * @param dst the destination FrameBuffer (modified)
     * @param copyColor true&rarr;copy color info, false&rarr;don't copy it
     * @param copyDepth true&rarr;copy depth info, false&rarr;don't copy it
     */
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyColor, boolean copyDepth);

    /**
     * Sets the framebuffer that will be drawn to.
     *
     * If the framebuffer has not been initialized yet, it will be created
     * and its render surfaces and attached textures will be allocated.
     *
     * @param fb The framebuffer to set
     */
    public void setFrameBuffer(FrameBuffer fb);

    /**
     * Sets the framebuffer that will be set instead of the main framebuffer
     * when a call to setFrameBuffer(null) is made.
     *
     * @param fb The framebuffer to override the main framebuffer.
     */
    public void setMainFrameBufferOverride(FrameBuffer fb);

    /**
     * Reads the pixels currently stored in the specified framebuffer
     * into the given ByteBuffer object.
     * Only color pixels are transferred, the format is RGBA with 8 bits
     * per component. The given byte buffer should have at least
     * fb.getWidth() * fb.getHeight() * 4 bytes remaining.
     *
     * @param fb The framebuffer to read from
     * @param byteBuf The bytebuffer to transfer color data to
     */
    public void readFrameBuffer(FrameBuffer fb, ByteBuffer byteBuf);

    /**
     * Reads the pixels currently stored in the specified framebuffer
     * into the given ByteBuffer object.
     * Only color pixels are transferred, with the given format.
     * The given byte buffer should have at least
     * fb.getWidth() * fb.getHeight() * 4 bytes remaining.
     *
     * @param fb The framebuffer to read from
     * @param byteBuf The bytebuffer to transfer color data to
     * @param format the image format to use when reading the frameBuffer.
     */
    public void readFrameBufferWithFormat(FrameBuffer fb, ByteBuffer byteBuf, Image.Format format);

    /**
     * Deletes a framebuffer and all attached renderbuffers.
     *
     * @param fb the FrameBuffer to be deleted
     */
    public void deleteFrameBuffer(FrameBuffer fb);

    /**
     * Assigns a Texture to the specified texture unit.
     *
     * @param unit the index of the texture unit (&ge;0)
     * @param tex the Texture to assign
     * @throws TextureUnitException if the texture unit doesn't exist
     */
    public void setTexture(int unit, Texture tex)
            throws TextureUnitException;
    
    /**
     * Assigns a TextureImage to the specified texture unit.
     * 
     * @param unit the index of the texture unit (&ge;0)
     * @param tex the texture image to assign
     * @throws TextureUnitException if the texture unit does not exist
     */
    public void setTextureImage(int unit, TextureImage tex) throws TextureUnitException;

    /**
     * Modifies the given Texture with the given Image.
     * The image will be put at x and y into the texture.
     *
     * NOTE: this is only supported for uncompressed 2D images without mipmaps.
     *
     * @param tex the Texture that will be modified
     * @param pixels the source Image data to copy data from
     * @param x the x position to put the image into the texture
     * @param y the y position to put the image into the texture
     */
    public void modifyTexture(Texture tex, Image pixels, int x, int y);

    /**
     * Upload a rectangular region of an uncompressed, non-mipmapped 2D image.
     * Does not update the destination CPU image. Call on the render thread.
     * Implementations without region uploads may throw UnsupportedOperationException.
     * <pre>{@code
     * // 第一步：更新目标纹理的 CPU 像素，但不要标记整张 Image.setUpdateNeeded()。
     * // 第二步：在绘制之前，仅上传变化矩形；首次分配仍由 setTexture 完成。
     * renderer.modifyTexture(atlas, atlas.getImage(), x, y, x, y, width, height);
     * // 第三步：不支持此接口的后端可标记整图更新，并用 setTexture 走兼容路径。
     * }</pre>
     * @param dest destination texture
     * @param src source CPU pixels
     * @param destX destination X
     * @param destY destination Y
     * @param srcX source X
     * @param srcY source Y
     * @param areaW width
     * @param areaH height
     */
    default void modifyTexture(com.jme3.texture.Texture2D dest, Image src,
            int destX, int destY, int srcX, int srcY, int areaW, int areaH) {
        throw new UnsupportedOperationException("Rectangular texture uploads unavailable");
    }

    /**
     * Uploads packed, native-order R32F samples into a non-mipmapped 3D texture.
     * Call on the render thread. CPU image contents are owned by the caller and
     * must also be updated for context restoration. Unsupported backends throw
     * UnsupportedOperationException; callers can mark the CPU image dirty instead.
     * <pre>{@code
     * // 第一步：更新 volume.getImage() 的 CPU 副本，保留上下文恢复所需数据。
     * // 第二步：把变化块按 x 连续、再 y、再 z 排列到 direct/native-order ByteBuffer。
     * block.flip();
     * // 第三步：绘制前上传；首次分配由 renderer 完成，不要把整张 Image 标记 dirty。
     * renderer.updateTexture3DRegion(volume, x, y, z, width, height, depth, block);
     * // 不支持的后端：捕获 UnsupportedOperationException，标记整图 setUpdateNeeded()。
     * }</pre>
     * @param texture destination R32F volume
     * @param x first destination column
     * @param y first destination row
     * @param z first destination slice
     * @param width block width
     * @param height block height
     * @param depth block depth
     * @param pixels packed direct buffer, exactly width*height*depth floats remaining
     */
    default void updateTexture3DRegion(com.jme3.texture.Texture3D texture,
            int x, int y, int z, int width, int height, int depth, java.nio.ByteBuffer pixels) {
        throw new UnsupportedOperationException("3D region uploads unavailable");
    }

    /**
     * Deletes a texture from the GPU.
     *
     * @param image the texture to delete
     */
    public void deleteImage(Image image);

    /**
     * Uploads a vertex buffer to the GPU.
     *
     * @param vb The vertex buffer to upload
     */
    public void updateBufferData(VertexBuffer vb);

    /**
     * Uploads data of the buffer object on the GPU.
     *
     * @param bo the buffer object to upload.
     */
    public void updateShaderStorageBufferObjectData(BufferObject bo);

    /**
     * Uploads data of the buffer object on the GPU.
     *
     * @param bo the buffer object to upload.
     */
    public void updateUniformBufferObjectData(BufferObject bo);
    
    /**
     * Blocking GPU buffer download. Does not upload pending CPU-side changes or
     * mutate the BufferObject's field values. Writes destination.remaining() bytes
     * without changing its position/limit. Render-thread only; may stall the GPU.
     * <pre>{@code
     * // 第一步：缓冲先由材质/renderer 上传或由 compute 写入。
     * // 第二步：准备 direct 缓冲；读取整数时使用 nativeOrder。
     * ByteBuffer bytes = BufferUtils.createByteBuffer(16);
     * renderer.readBufferData(buffer, 0, bytes);
     * int value = bytes.getInt(0);
     * // 第三步：FG 中把该 Pass 声明为 read(handle, ResourceAccess.BUFFER_TRANSFER)。
     * // 同一 SSBO 被 shader 读取则用 STORAGE_BUFFER；此处是下载操作。
     * // 调试或偶尔取结果可用；不要用每帧阻塞读回来实现常规渲染管线。
     * }</pre>
     * @param buffer already allocated GPU buffer
     * @param byteOffset non-negative byte offset in GPU storage
     * @param destination direct writable buffer; current position/limit delimit the download
     */
    default void readBufferData(BufferObject buffer, long byteOffset, ByteBuffer destination) {
        throw new UnsupportedOperationException("Buffer readback is unavailable");
    }


    /**
     * Deletes a vertex buffer from the GPU.
     *
     * @param vb The vertex buffer to delete
     */
    public void deleteBuffer(VertexBuffer vb);

    /**
     * Deletes the buffer object from the GPU.
     *
     * @param bo the buffer object to delete.
     */
    public void deleteBuffer(BufferObject bo);

    /**
     * Renders <code>count</code> meshes, with the geometry data supplied and
     * per-instance data supplied.
     * The shader which is currently set with <code>setShader</code> is
     * responsible for transforming the input vertices into clip space
     * and shading it based on the given vertex attributes.
     * The integer variable gl_InstanceID can be used to access the current
     * instance of the mesh being rendered inside the vertex shader.
     * If the instance data is non-null, then it is submitted as a
     * per-instance vertex attribute to the shader.
     *
     * @param mesh The mesh to render
     * @param lod The LOD level to use, see {@link Mesh#setLodLevels(com.jme3.scene.VertexBuffer[]) }.
     * @param count Number of mesh instances to render
     * @param instanceData When count is greater than 1, these buffers provide
     *     the per-instance attributes.
     */
    public void renderMesh(Mesh mesh, int lod, int count, VertexBuffer[] instanceData);

    /**
     * Resets all previously used {@link NativeObject Native Objects} on this Renderer.
     * The state of the native objects is reset in such way, that using
     * them again will cause the renderer to reupload them.
     * Call this method when you know the GL context is going to shut down.
     *
     * @see NativeObject#resetObject()
     */
    public void resetGLObjects();

    /**
     * Deletes all previously used {@link NativeObject Native Objects} on this Renderer, and
     * then resets the native objects.
     *
     * @see #resetGLObjects()
     * @see NativeObject#deleteObject(java.lang.Object)
     */
    public void cleanup();

    /**
     * Sets the default anisotropic filter level for textures.
     *
     * <p>If the
     * {@link Texture#setAnisotropicFilter(int) texture anisotropic filter} is
     * set to 0, then the default level is used. Otherwise, if the texture level
     * is 1 or greater, then the texture's value overrides the default value.
     *
     * @param level The default anisotropic filter level to use. Default: 1.
     *
     * @throws IllegalArgumentException If level is less than 1.
     */
    public void setDefaultAnisotropicFilter(int level);

    /**
     * Sets the alpha to coverage state.
     *
     * <p>When alpha coverage and multi-sampling is enabled,
     * each pixel will contain alpha coverage in all
     * of its subsamples, which is then combined when
     * other future alpha-blended objects are rendered.</p>
     *
     * <p>Alpha-to-coverage is useful for rendering transparent objects
     * without having to worry about sorting them.
     * </p>
     *
     * @param value true to enable alpha coverage, otherwise false
     */
    public void setAlphaToCoverage(boolean value);

    /**
     * Specifies whether color values in the main framebuffer are in SRGB format.
     *
     * <p>If enabled, color values rendered to the main framebuffer undergo
     * linear -&gt; sRGB conversion.
     *
     * <p>This is identical to {@link FrameBuffer#setSrgb(boolean)} except it is toggled
     * for the main framebuffer instead of an offscreen buffer.
     *
     * <p>This should be set together with {@link Renderer#setLinearizeSrgbImages(boolean)}
     *
     * <p>As a shorthand, the user can set {@link AppSettings#setGammaCorrection(boolean)} to true
     * to toggle both {@link Renderer#setLinearizeSrgbImages(boolean)} and
     * {@link Renderer#setMainFrameBufferSrgb(boolean)} if the
     * {@link Caps#Srgb} and {@link Caps#SrgbWriteControl} capabilities are
     * supported by the GPU.
     *
     * @param srgb true for sRGB colorspace, false for linear colorspace
     * @throws RendererException If the GPU hardware does not support sRGB.
     *
     * @see FrameBuffer#setSrgb(boolean)
     * @see Caps#Srgb
     * @see Caps#SrgbWriteControl
     */
    public void setMainFrameBufferSrgb(boolean srgb);

    /**
     * If enabled, all {@link Image images} with the
     * {@link Image#setColorSpace(com.jme3.texture.image.ColorSpace) sRGB flag}
     * set shall undergo an sRGB to linear RGB color conversion when read by a shader.
     *
     * <p>The conversion is performed for the following formats:
     *  - {@link com.jme3.texture.Image.Format#RGB8}
     *  - {@link com.jme3.texture.Image.Format#RGBA8}
     *  - {@link com.jme3.texture.Image.Format#Luminance8}
     *  - {@link com.jme3.texture.Image.Format#Luminance8Alpha8}
     *  - {@link com.jme3.texture.Image.Format#DXT1}
     *  - {@link com.jme3.texture.Image.Format#DXT1A}
     *  - {@link com.jme3.texture.Image.Format#DXT3}
     *  - {@link com.jme3.texture.Image.Format#DXT5}
     *
     * <p>For all other formats, no conversion is performed.
     *
     * <p>If this option is toggled at runtime, textures must be reloaded for the change to take effect.
     *
     * @param linearize If sRGB images undergo sRGB -&gt; linear conversion prior to rendering.
     * @throws RendererException If the GPU hardware does not support sRGB.
     *
     * @see Caps#Srgb
     */
    public void setLinearizeSrgbImages(boolean linearize);

    /**
     * Generates a pool of gpu queries meant to use as profiling tasks.
     *
     * @param numTasks the number of task ids to generate
     * @return an array of tasks ids.
     */
    public int[] generateProfilingTasks(int numTasks);

    /**
     * Releases GPU profiling query IDs on the render thread. End any active query
     * first. See startProfiling/isTaskResultAvailable; queries need not be complete
     * before deletion. The OpenGL renderer owns final context-time cleanup; releasing
     * an already deleted query afterward is a no-op. Use finally/cleanup to pair calls.
     * <pre>{@code
     * // 第一步：分配并记录，不能嵌套 elapsed-time 查询。
     * int[] ids = renderer.generateProfilingTasks(1);
     * renderer.startProfiling(ids[0]);
     * // 执行需要计时的 GPU 工作。
     * renderer.stopProfiling();
     * // 第二步：后续帧先检查可用性，再读取，不能假定下一帧必定完成。
     * if (renderer.isTaskResultAvailable(ids[0])) {
     *     long nanoseconds = renderer.getProfilingTime(ids[0]);
     * }
     * // 第三步：工具退出时释放查询。
     * renderer.deleteProfilingTasks(ids);
     * }</pre>
     * @param taskIds owned query identifiers
     */
    default void deleteProfilingTasks(int... taskIds) {
        throw new UnsupportedOperationException("Profiling query deletion unavailable");
    }

    /**
     * Copies a GPU range to an owned asynchronous snapshot; does not wait for it.
     * Requires backend support and an allocated source. Render thread only.
     * See {@link BufferReadback} for a complete polling/cleanup usage example.
     * @param source GPU buffer to snapshot, borrowed
     * @param offset non-negative source byte offset
     * @param size positive byte count
     * @return owned request, closed by caller or renderer cleanup
     */
    default BufferReadback requestBufferReadback(com.jme3.shader.bufferobject.BufferObject source, long offset, int size) {
        throw new UnsupportedOperationException("Asynchronous buffer readback unavailable");
    }

    /**
     * Starts a time profiling task on the GPU.
     * This will profile all operations called between startProfiling and stopProfiling
     *
     * @param taskId the id of the task to start profiling.
     */
    public void startProfiling(int taskId);

    /** @return whether an elapsed-time query is already active; such queries cannot nest */
    default boolean isProfilingActive() {
        return false;
    }

    /**
     * Will stop the last profiling task started with startProfiling.
     */
    public void stopProfiling();

    /**
     * Returns the time in nanoseconds elapsed for the task with the given id.
     * Note that the result may not be available right after stopProfiling has been called.
     * You need to check if the result is available with isTaskResultAvailable.
     * Availability depends on queued GPU work. Check isTaskResultAvailable before reading
     * even on later frames; an unconditional read can stall the calling thread.
     *
     * @param taskId the id of the task given by startProfiling.
     * @return the time in nanosecond of the profiling task with the given id.
     */
    public long getProfilingTime(int taskId);

    /**
     * Checks if the profiling results are available.
     *
     * @param taskId the id of the task provided by startProfiling
     * @return true if the results of the task with the given task id are available.
     */
    public boolean isTaskResultAvailable(int taskId);

    /**
     * Reports whether the GPU timer clock became discontinuous since the previous check.
     * On GLES, reading this context-wide flag resets it. Coordinate checks between profiling
     * clients: check once before starting an interval, poll result availability, then check
     * again before consuming those results. If true, discard all completed and pending
     * timings from that interval and start a new interval. Affected values are invalid even
     * if their results became available before the check.
     * Desktop OpenGL timer queries do not expose a disjoint flag; an OpenGL renderer returns
     * false there. GLES renderers use the EXT_disjoint_timer_query disjoint state.
     * The default implementation returns false for renderers without a disjoint flag;
     * this does not imply support for GPU timer queries.
     *
     * @return true if a GPU timer discontinuity occurred since the previous check
     * @throws RendererException if GPU timer queries are unsupported by the current context
     */
    public default boolean isGpuTimingDisjoint() {
        return false;
    }

    /**
     * Gets the alpha to coverage state.
     *
     * @return true if alpha coverage is enabled, otherwise false
     */
    public boolean getAlphaToCoverage();

    /**
     * Gets the default anisotropic filter level for textures.
     *
     * @return the default filter level
     */
    public int getDefaultAnisotropicFilter();

    /**
     * Determines the maximum allowed width for lines.
     *
     * @return the maximum width (in pixels)
     */
    public float getMaxLineWidth();

    /**
     * Tests whether images with the sRGB flag will be linearized when read by a
     * shader.
     *
     * @return true for linearization, false for no linearization
     */
    public boolean isLinearizeSrgbImages();

    /**
     * Tests whether colors rendered to the main framebuffer undergo
     * linear-to-sRGB conversion.
     *
     * @return true for conversion, false for no conversion
     */
    public boolean isMainFrameBufferSrgb();

    public void setShaderStorageBufferObject(int bindingPoint, BufferObject bufferObject) ;
    public void setUniformBufferObject(int bindingPoint, BufferObject bufferObject) ;

    public void deleteFence(GLFence fence);

    /**
     * Registers a NativeObject to be cleaned up by this renderer.
     */
    public void registerNativeObject(NativeObject nativeObject);

    public default Format getBestColorTargetFormat(boolean floatingPoint) {
        return getBestColorTargetFormat(floatingPoint, true, false);
    }

    /**
     * Returns the best color target format based on the requested properties and renderer capabilities.
     *
     * @param floatingPoint true if a floating point format is required.
     * @param highPrecision true if high precision (e.g. 16-bit or 32-bit) is preferred over packed formats.
     * @param withAlpha true if an alpha channel is required.
     * @return the best matching Image.Format.
     */
    public default Format getBestColorTargetFormat(boolean floatingPoint, boolean highPrecision, boolean withAlpha) {
        return getBestColorTargetFormat(floatingPoint, highPrecision, withAlpha, true);
    }

    /**
     * Returns the best color target format based on the requested properties and renderer capabilities.
     *
     * @param floatingPoint true if a floating point format is required.
     * @param highPrecision true if high precision (e.g. 16-bit or 32-bit) is preferred over packed formats.
     * @param withAlpha true if an alpha channel is required.
     * @param supportPackedFloat true if packed float formats (e.g. RGB111110F) are allowed.
     * @return the best matching Image.Format.
     */
    public default Format getBestColorTargetFormat(boolean floatingPoint, boolean highPrecision, boolean withAlpha, boolean supportPackedFloat) {
        if (!floatingPoint) {
            return Format.RGBA8;
        }

        if (!highPrecision && !withAlpha) {
            if (supportPackedFloat && getCaps().contains(Caps.PackedFloatTexture)
                    && getCaps().contains(Caps.PackedFloatColorBuffer)) {
                return Format.RGB111110F;
            }
        }

        if (withAlpha) {
            if (getCaps().contains(Caps.HalfFloatTexture)
                    && getCaps().contains(Caps.HalfFloatColorBufferRGBA)) {
                return Format.RGBA16F;
            }
        } else {
            if (getCaps().contains(Caps.HalfFloatTexture)
                    && getCaps().contains(Caps.HalfFloatColorBufferRGB)) {
                return Format.RGB16F;
            } else if (getCaps().contains(Caps.HalfFloatTexture)
                    && getCaps().contains(Caps.HalfFloatColorBufferRGBA)) {
                return Format.RGBA16F;
            } else if (supportPackedFloat && getCaps().contains(Caps.PackedFloatTexture)
                    && getCaps().contains(Caps.PackedFloatColorBuffer)) {
                return Format.RGB111110F;
            } 
        }

        return Format.RGBA8;
    }

    public default Format getBestDepthTargetFormat() {
        return getBestDepthTargetFormat(false, false, false);
    }

    public default Format getBestDepthTargetFormat(boolean floatingPoint, boolean highPrecision, boolean withStencil) {
        if (withStencil) {
            if (getCaps().contains(Caps.PackedDepthStencilBuffer)) {
                return Format.Depth24Stencil8;
            }
        } else {
            if (floatingPoint && getCaps().contains(Caps.FloatDepthBuffer)) {
                return Format.Depth32F;
            }
            if (highPrecision) {
                if (getCaps().contains(Caps.Depth32)) {
                    return Format.Depth32;
                }
                if (getCaps().contains(Caps.FloatDepthBuffer)) {
                    return Format.Depth32F;
                }
            }
            if (getCaps().contains(Caps.Depth24)) {
                return Format.Depth24;
            }
        }

        return Format.Depth;
    }

    /**
     * Submits compacted indexed commands with a GPU-resident count. The original
     * command list is required for nonblocking statistics and capability fallback;
     * it must describe exactly the same visible work, in its original order.
     * All commands contain five uints (20 bytes). Count is one uint at offset zero.
     * Call on the render thread after a command/storage barrier. Buffers remain
     * caller-owned and must live through submission. Unsupported renderers submit
     * the original fixed-length list without a synchronous count readback.
     * <pre>{@code
     * // 第一步：计算剔除结果，然后压缩非空命令并生成 GPU count。
     * compactMaterial.dispatchCompute(renderManager, groups, 1, 1);
     * // 第二步：让计算结果对间接命令读取可见。
     * renderer.memoryBarrier(0x2041);
     * // 第三步：通过 Renderer 提交；不支持计数提交时自动使用原始列表。
     * renderer.renderMeshCompactedIndirect(mesh, compacted, count, original,
     *         maxDraws, attributes, sourceVertexCounts);
     * }</pre>
     */
    default void renderMeshCompactedIndirect(com.jme3.scene.Mesh mesh,
            com.jme3.shader.bufferobject.BufferObject compacted, com.jme3.shader.bufferobject.BufferObject count,
            com.jme3.shader.bufferobject.BufferObject original, int maxDraws,
            com.jme3.scene.VertexBuffer[] attributes, int[] sourceVertexCounts) {
        renderMeshMultiDrawIndirect(mesh, original, 0, maxDraws, attributes, sourceVertexCounts);
    }

    /**
     * Submits tightly packed indexed indirect commands sharing one mesh index buffer,
     * shader, material and render state. GPU commands are five uints (20 bytes)
     * each; firstIndex is measured in indices, not bytes. Zero-instance commands
     * are allowed. Caller provides COMMAND and VERTEX_ATTRIB_ARRAY barriers after
     * compute writes. Render thread/current context only; no ownership transfer.
     * The default implementation falls back to individual indirect submissions.
     * <pre>{@code
     * // 第一步：绑定相同材质/Shader，准备共享顶点及打包后的索引缓冲。
     * // 第二步：GPU 写入 commands 后，确保间接命令与实例属性对绘制可见。
     * renderer.memoryBarrier(0x2041);
     * // 第三步：每条命令通过 firstIndex/count 选择对应 LOD 的索引范围。
     * renderer.renderMeshMultiDrawIndirect(mesh, commands, 0, lodCount, instanceAttributes);
     * // 第四步：不支持合并的 Renderer 默认逐条提交，结果保持一致。
     * }</pre>
     * @param mesh mesh with a shared index buffer (LOD zero)
     * @param commands tightly packed GPU command buffer
     * @param byteOffset aligned byte offset of the first command
     * @param drawCount number of commands, nonnegative
     * @param instanceData borrowed instance attributes
     */
    default void renderMeshMultiDrawIndirect(com.jme3.scene.Mesh mesh,
            com.jme3.shader.bufferobject.BufferObject commands, long byteOffset, int drawCount,
            com.jme3.scene.VertexBuffer[] instanceData) {
        if (drawCount < 0 || byteOffset < 0 || (byteOffset & 3) != 0) {
            throw new IllegalArgumentException("Invalid multi-draw range");
        }
        for (int i = 0; i < drawCount; i++) {
            renderMeshIndirect(mesh, 0, commands, byteOffset + i * 20L, instanceData);
        }
    }
    /**
     * Material integration hook: returns a compatible bindless sampler variant,
     * or the original shader when unsupported. Renderer owns caching/lifecycle.
     * Called before material/world uniforms are assigned, on the render thread.
     * Applications use Material.setBindlessTextures, not shader-source registration.
     * @param original ordinary material shader
     * @return compatible variant or the unchanged input
     */
    default com.jme3.shader.Shader getBindlessShader(com.jme3.shader.Shader original) {
        return original;
    }

    /**
     * Binds a material sampler and returns the next free texture unit. Bindless
     * requests may fall back per texture (e.g. render targets or a full cache).
     * Internal render-thread material hook; never transfers texture ownership.
     * @param unit next available conventional texture unit
     * @param texture texture parameter
     * @param uniform sampler uniform
     * @param bindless whether the shader supports handle assignments
     * @return next free conventional unit
     */
    default int setTextureParameter(int unit, com.jme3.texture.Texture texture,
            com.jme3.shader.Uniform uniform, boolean bindless) throws TextureUnitException {
        setTexture(unit, texture);
        uniform.setValue(com.jme3.shader.VarType.Int, unit);
        return unit + 1;
    }

    /** @return resident managed bindless snapshots; zero on unsupported renderers */
    default int getBindlessTextureCount() {
        return 0;
    }

    /** @return approximate resident managed snapshot bytes; excludes ordinary textures */
    default long getBindlessTextureBytes() {
        return 0;
    }
    /**
     * Multi-draw variant with per-command source vertex counts for accurate
     * nominal vertex statistics when several meshes share packed vertex storage.
     * Same context, barrier and ownership rules as renderMeshMultiDrawIndirect.
     * Counts are borrowed only for this call, not GPU vertex-shader invocations.
     * <pre>{@code
     * // 第一步：打包多个网格，各条命令保留各自的 baseVertex/firstIndex。
     * // 第二步：提供源网格顶点数，避免把整个共享顶点池计入每条命令。
     * int[] sourceVertices = {tankMesh.getVertexCount(), boatMesh.getVertexCount()};
     * renderer.renderMeshMultiDrawIndirect(packedMesh, commands, 0, 2, attributes, sourceVertices);
     * }</pre>
     * @param mesh shared packed mesh
     * @param commands tightly packed indirect commands
     * @param byteOffset first command byte offset
     * @param drawCount number of commands
     * @param instanceData instance attributes
     * @param sourceVertexCounts per-command vertex counts, or null for mesh vertex count
     */
    default void renderMeshMultiDrawIndirect(com.jme3.scene.Mesh mesh,
            com.jme3.shader.bufferobject.BufferObject commands, long byteOffset, int drawCount,
            com.jme3.scene.VertexBuffer[] instanceData, int[] sourceVertexCounts) {
        renderMeshMultiDrawIndirect(mesh, commands, byteOffset, drawCount, instanceData);
    }
}
