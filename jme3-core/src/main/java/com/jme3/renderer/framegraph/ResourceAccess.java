package com.jme3.renderer.framegraph;

/**
 * GPU access intent, used to synchronize incoherent shader writes on the GL backend.
 * Whole resources are tracked: no mip/layer ranges or cross-queue synchronization.
 * GENERAL is conservative when consuming shader output; ordinary framebuffer writes
 * require no explicit memory barrier. CPU mutations do not count as shader writes.
 * <pre>{@code
 * // 第一步：compute 的 SSBO 输出必须明确声明 STORAGE_BUFFER。
 * graph.addPass("Compute", context -> dispatch())
 *         .write(buffer, ResourceAccess.STORAGE_BUFFER);
 * // 第二步：消费方声明真实用途，FG 会在它之前插入对应屏障。
 * graph.addPass("Draw", context -> draw())
 *         .read(buffer, ResourceAccess.VERTEX_BUFFER).sideEffect();
 * // 第三步：同一 SSBO 改为读回 CPU，此时用途是 BUFFER_TRANSFER。
 * graph.addPass("Readback", context -> renderer.readBufferData(context.get(buffer), 0, bytes))
 *         .read(buffer, ResourceAccess.BUFFER_TRANSFER).sideEffect();
 * // imageStore 输出使用 STORAGE_IMAGE，随后采样使用 SAMPLED_TEXTURE。
 * // shader 内工作组同步仍需 GLSL barrier；本枚举只处理 Pass 间同步。
 * }</pre>
 */
public enum ResourceAccess {
    /**
     * Unspecified consumer usage; conservatively publishes all shader-write domains.
     * 未细分的普通访问，适合旧代码兼容；不是“任意 shader 写入”。
     * compute 写 SSBO/image 时必须显式选 STORAGE_BUFFER/STORAGE_IMAGE，不能用它代替。
     */
    GENERAL(-1, false),
    /**
     * Framebuffer attachment reads/writes, blending, clearing and framebuffer blits.
     * 用于渲染目标：绘制、透明混合、清屏、copyFrameBuffer、readFrameBuffer。
     * 例：.write(color, FRAMEBUFFER)；读取目标做 blit 时 .read(color, FRAMEBUFFER)。
     */
    FRAMEBUFFER(0x400, false),
    /**
     * Texture sampling through GLSL sampler uniforms.
     * shader 用 texture()/texelFetch() 采样纹理时使用，例如后处理读取场景颜色。
     * 例：.read(sceneColor, SAMPLED_TEXTURE)，不是 imageLoad 或 FBO 读回。
     */
    SAMPLED_TEXTURE(0x8, false),
    /**
     * Shader image load/store access, not sampler access.
     * shader 通过 imageLoad/imageStore 访问图像；读用 read，写用 write。
     * 例：.write(image, STORAGE_IMAGE)，随后采样它的 Pass 改用 SAMPLED_TEXTURE。
     */
    STORAGE_IMAGE(0x20, true),
    /**
     * Shader storage block (SSBO) reads/writes performed by a shader.
     * GPU 的 shader 通过 buffer 块访问数据，不限于 compute，也可在图形 shader 中使用。
     * 例：剔除 compute .write(indices, STORAGE_BUFFER)，光照 shader .read(indices, STORAGE_BUFFER)。
     * 同一缓冲若随后由 CPU 读回，该读回 Pass 使用 BUFFER_TRANSFER，不再是此枚举。
     */
    STORAGE_BUFFER(0x2000, true),
    /**
     * Vertex attribute fetch by the draw pipeline.
     * 缓冲作为顶点位置/法线等顶点输入时 .read(vertices, VERTEX_BUFFER)。
     * compute 生成这些数据的生产阶段仍然 .write(vertices, STORAGE_BUFFER)。
     */
    VERTEX_BUFFER(0x1, false),
    /**
     * Index fetch for indexed drawing.
     * 缓冲用作索引输入时 .read(indices, INDEX_BUFFER)，不等同于 shader 读取 SSBO。
     */
    INDEX_BUFFER(0x2, false),
    /**
     * Uniform block (UBO) reads by a shader.
     * shader 通过 uniform 块读取参数时使用；和可写 buffer 块的 STORAGE_BUFFER 区分。
     * 例：.read(cameraParameters, UNIFORM_BUFFER)。
     */
    UNIFORM_BUFFER(0x4, false),
    /**
     * Indirect draw/dispatch command fetch.
     * GPU 将缓冲内容作为间接绘制/dispatch 指令读取时使用。
     * 例：compute 生成命令用 STORAGE_BUFFER，消费命令用 INDIRECT_COMMAND。
     * 只声明同步用途，不会自动提供或执行间接绘制 API。
     */
    INDIRECT_COMMAND(0x40, false),
    /**
     * Texture upload/download/copy API access, excluding framebuffer blits.
     * 使用纹理数据上传、纹理图像下载或直接纹理复制 API 时声明。
     * 从 FBO 读取像素/readFrameBuffer 或 copyFrameBuffer 应选 FRAMEBUFFER。
     * 若通过 PBO 搬运像素，PBO 端的像素传输尚无专用枚举，可保守使用 GENERAL。
     */
    TEXTURE_TRANSFER(0x100, false),
    /**
     * Buffer upload/download/copy API access outside shader execution.
     * renderer.readBufferData 把 GPU 缓冲读回 CPU 时：.read(buffer, BUFFER_TRANSFER)。
     * CPU 上传或缓冲复制目标为 .write(buffer, BUFFER_TRANSFER)，复制源为 read。
     * 选择依据是“本 Pass 怎样访问”，不是缓冲创建时叫 SSBO 还是 UBO。
     * memory barrier 只保证 GPU 可见性；阻塞读回 API 才完成 CPU 等待。
     */
    BUFFER_TRANSFER(0x200, false);

    final int barrierBits;
    final boolean shaderStorage;

    ResourceAccess(int barrierBits, boolean shaderStorage) {
        this.barrierBits = barrierBits;
        this.shaderStorage = shaderStorage;
    }
}
