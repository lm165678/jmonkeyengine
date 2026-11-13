/*
 * Copyright (c) 2009-2019 jMonkeyEngine
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
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.BufferObject;
import com.jme3.shader.Shader;
import com.jme3.shader.Shader.ShaderSource;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.util.NativeObject;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;

/**
 * The <code>Renderer</code> is responsible for taking rendering commands and
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
     * Get the capabilities of the renderer.
     * @return The capabilities of the renderer.
     */
    public EnumSet<Caps> getCaps();

    /**
     * Get the limits of the renderer.
     *
     * @return The limits of the renderer.
     */
    public EnumMap<Limits, Integer> getLimits();

    /**
     * The statistics allow tracking of how data
     * per frame, such as number of objects rendered, number of triangles, etc.
     * These are updated when the Renderer's methods are used, make sure
     * to call {@link Statistics#clearFrame() } at the appropriate time
     * to get accurate info per frame.
     */
    public Statistics getStatistics();

    /**
     * Invalidates the current rendering state. Should be called after
     * the GL state was changed manually or through an external library.
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
     */
    public void applyRenderState(RenderState state);

    /**
     * Set the range of the depth values for objects. All rendered
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
     * Set the viewport location and resolution on the screen.
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
     */
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyDepth);

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
     * Set the framebuffer that will be set instead of the main framebuffer
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
     * Deletes a framebuffer and all attached renderbuffers
     */
    public void deleteFrameBuffer(FrameBuffer fb);

    /**
     * Sets the texture to use for the given texture unit.
     */
    public void setTexture(int unit, Texture tex);

    /**
     * Modify the given Texture with the given Image. 
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
    public void updateBufferData(BufferObject bo);

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
     *                     the per-instance attributes.
     */
    public void renderMesh(Mesh mesh, int lod, int count, VertexBuffer[] instanceData);

    /**
     * Resets all previously used {@link NativeObject Native Objects} on this Renderer.
     * The state of the native objects is reset in such way, that using
     * them again will cause the renderer to reupload them.
     * Call this method when you know the GL context is going to shutdown.
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
     * Set the default anisotropic filter level for textures.
     *
     * If the
     * {@link Texture#setAnisotropicFilter(int) texture anisotropic filter} is
     * set to 0, then the default level is used. Otherwise if the texture level
     * is 1 or greater, then the texture's value overrides the default value.
     *
     * @param level The default anisotropic filter level to use. Default: 1.
     *
     * @throws IllegalArgumentException If level is less than 1.
     */
    public void setDefaultAnisotropicFilter(int level);

    /**
     * Sets the alpha to coverage state.
     * <p>
     * When alpha coverage and multi-sampling is enabled, 
     * each pixel will contain alpha coverage in all
     * of its subsamples, which is then combined when
     * other future alpha-blended objects are rendered.
     * </p>
     * <p>
     * Alpha-to-coverage is useful for rendering transparent objects
     * without having to worry about sorting them.
     * </p>
     */
    public void setAlphaToCoverage(boolean value);
    
      /**
      * If enabled, color values rendered to the main framebuffer undergo 
      * linear -&gt; sRGB conversion.
      * 
      * This is identical to {@link FrameBuffer#setSrgb(boolean)} except it is toggled
      * for the main framebuffer instead of an offscreen buffer.
      *
      * This should be set together with {@link Renderer#setLinearizeSrgbImages(boolean)}
      *
      * As a shorthand, the user can set {@link AppSettings#setGammaCorrection(boolean)} to true
      * to toggle both {@link Renderer#setLinearizeSrgbImages(boolean)} and
      * {@link Renderer#setMainFrameBufferSrgb(boolean)} if the 
      * {@link Caps#Srgb} is supported by the GPU.
      *
      * @throws RendererException If the GPU hardware does not support sRGB.
      *
      * @see FrameBuffer#setSrgb(boolean)
      * @see Caps#Srgb
      */
     public void setMainFrameBufferSrgb(boolean srgb);
     
       /**
      * If enabled, all {@link Image images} with the {@link Image#setColorSpace(com.jme3.texture.image.ColorSpace) sRGB flag}
      * set shall undergo an sRGB to linear RGB color conversion when read by a shader.
      *
      * The conversion is performed for the following formats:
      *  - {@link com.jme3.texture.Image.Format#RGB8}
      *  - {@link com.jme3.texture.Image.Format#RGBA8}
      *  - {@link com.jme3.texture.Image.Format#Luminance8}
      *  - {@link com.jme3.texture.Image.Format#Luminance8Alpha8}
      *  - {@link com.jme3.texture.Image.Format#DXT1}
      *  - {@link com.jme3.texture.Image.Format#DXT1A}
      *  - {@link com.jme3.texture.Image.Format#DXT3}
      *  - {@link com.jme3.texture.Image.Format#DXT5}
      * 
      * For all other formats, no conversion is performed.
      *
      * If this option is toggled at runtime, textures must be reloaded for the change to take effect.
      *
      * @throws RendererException If the GPU hardware does not support sRGB.
      *
      * @param linearize If sRGB images undergo sRGB -&gt; linear conversion prior to rendering.
      *
      * @see Caps#Srgb
      */
     public void setLinearizeSrgbImages(boolean linearize);


    /**
     * Generates a pool of gpu queries meant to use as profiling tasks
     *
     * @param numTasks the number of task ids to generate
     * @return an array of tasks ids.
     */
    public int[] generateProfilingTasks(int numTasks);

    /**
     * Starts a time profiling task on the GPU.
     * This will profile all operations called between startProfiling and stopProfiling
     *
     * @param taskId the id of the task to start profiling.
     */
    public void startProfiling(int taskId);

    /**
     * Will stop the last profiling task started with startProfiling
     */
    public void stopProfiling();

    /**
     * Returns the time in nano seconds elapsed for the task with the given id.
     * Note that the result may not be available right after stopProfiling has been called.
     * You need to check if the result is available with isTaskResultAvailable.
     * Also note that it's guaranteed that the result will be available on next frame.
     * If you use getProfilingTime on the next frame you called stopProfiling, you don't need to check the result availability with isTaskResultAvailable
     *
     * @param taskId the id of the task given by startProfiling.
     * @return the time in nano second of the profiling task with the given id.
     */
    public long getProfilingTime(int taskId);

    /**
     * Check if the profiling results are available
     *
     * @param taskId the id of the task provided by startProfiling
     * @return true if the results of the task with the given task id are available.
     */
    public boolean isTaskResultAvailable(int taskId);
    
    
    /**
     * Gets the alpha to coverage state.
     * 
     */
    public boolean getAlphaToCoverage(); 
    
    /**
     * Get the default anisotropic filter level for textures.
     *
     */
    public int getDefaultAnisotropicFilter();

}
