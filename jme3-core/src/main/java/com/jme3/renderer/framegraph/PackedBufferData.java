package com.jme3.renderer.framegraph;

import com.jme3.shader.bufferobject.BufferObject;
import java.nio.ByteBuffer;

/**
 * Internal packing for the tightly packed scalar arrays consumed by custom
 * compute shaders. Uses upstream buffer ownership and reuses CPU storage when
 * the byte count is unchanged. Render-thread only; buffers remain caller-owned.
 * Scalars occupy four native-order bytes each; this is not a general std140
 * structure packer. Materials choose the actual binding slot when dispatched.
 * <pre>{@code
 * // 第一步：创建缓冲，按 shader 的标量数组布局准备初始数据。
 * BufferObject values = PackedBufferData.create(0);
 * PackedBufferData.set(values, new int[]{0, 1, 2, 3});
 * // 第二步：通过 j3md 的 ShaderStorageBufferObject 参数绑定。
 * Material compute = new Material(assetManager, "Materials/FrameGraph/VersionCompute.j3md");
 * compute.setShaderStorageBufferObject("Values", values);
 * compute.dispatchCompute(renderManager, 1, 1, 1);
 * // 第三步：后续更新复用同尺寸存储；不再使用时由资源拥有者释放。
 * PackedBufferData.set(values, new int[]{4, 5, 6, 7});
 * values.dispose();
 * }</pre>
 */
public final class PackedBufferData {
    private PackedBufferData() {
    }

    public static BufferObject create(int binding) {
        BufferObject buffer = new BufferObject();
        buffer.setBinding(binding);
        return buffer;
    }

    private static ByteBuffer storage(BufferObject buffer, int count) {
        int bytes = Math.multiplyExact(count, 4);
        ByteBuffer data = buffer.getData();
        if (data.capacity() != bytes) {
            buffer.initializeEmpty(bytes);
            data = buffer.getData();
        }
        data.clear();
        return data;
    }

    public static void set(BufferObject buffer, int[] values) {
        ByteBuffer data = storage(buffer, values.length);
        data.asIntBuffer().put(values);
        buffer.setUpdateNeeded();
    }

    public static void set(BufferObject buffer, float[] values) {
        ByteBuffer data = storage(buffer, values.length);
        data.asFloatBuffer().put(values);
        buffer.setUpdateNeeded();
    }
}
