package com.jme3.renderer;

import java.nio.ByteBuffer;

/**
 * Owned asynchronous snapshot of a GPU buffer range. All methods require the
 * owning render thread/context. Closing releases the snapshot, not its source.
 * Polling never waits. The caller must close completed or abandoned requests.
 * <pre>{@code
 * // 第一步：GPU 写入结束后申请快照，后续源缓冲更新不会改变这份快照。
 * BufferReadback request = renderer.requestBufferReadback(buffer, 0, 20);
 * // 第二步：后续帧轮询；未完成就跳过，不阻塞游戏线程。
 * if (request.isReady()) {
 *     request.read(destination); // direct ByteBuffer，remaining 至少 20 字节。
 *     request.close();
 * }
 * // 第三步：退出工具/切换场景时，未完成的请求也必须 close。
 * }</pre>
 */
public interface BufferReadback extends AutoCloseable {
    /** @return whether the snapshot can be read without waiting for GPU completion */
    boolean isReady();

    /**
     * Reads a completed snapshot into the destination without changing its position.
     * @param destination direct writable buffer with enough remaining bytes
     * @throws IllegalStateException if pending, closed, or invalidated by context loss
     */
    void read(ByteBuffer destination);

    /** Releases this snapshot; idempotent, including after renderer cleanup. */
    @Override
    void close();
}
