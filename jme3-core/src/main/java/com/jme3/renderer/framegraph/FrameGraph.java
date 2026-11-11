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

import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Render-thread resource dependency graph with stable topological scheduling,
 * dead-pass elimination, lazy allocation and lifetime-based object pooling.
 * Build once, compile once, execute repeatedly. Configuration is frozen by compile;
 * call beginFrame() to rebuild topology while retaining allocations. Dynamic imported suppliers
 * are evaluated once per execution. See package documentation for a full example.
 *
 * <p>Each logical resource has at most one writer. Use separate logical resources
 * for ping-pong operations, or nextVersion for ordered in-place updates; do not declare a read/write
 * texture feedback loop in one pass. Write declarations do not initialize data.
 * Exported outputs and side-effect/import-writing passes keep their dependencies
 * alive. Unused passes allocate nothing. Imported objects are never disposed by
 * this graph. Equal definitions reuse physical objects only after last use.</p>
 *
 * <p>This is a single graphics-queue graph, appropriate for jME's OpenGL renderer.
 * It does not introduce parallel GL contexts or backend-specific memory aliasing.
 * A graph instance is owned by one viewport; use one graph/pool per viewport.</p>
 */
public final class FrameGraph implements AutoCloseable {
    private static final class Resource {
        ResourceHandle<?> handle;
        ResourceDefinition<?> definition;
        Supplier<?> imported;
        Object importedValue;
        boolean importedResolved;
        int writer = -1, first = -1, last = -1;
        boolean exported;
        Object value;
        boolean resolved;
        Slot slot;
        Persistent persistent;
        Resource previous;
        Resource next;
        Resource storage;
    }
    private static final class Persistent {
        ResourceDefinition definition;
        Object value;
        boolean valid;
        int width;
        int height;
    }
    private static final class Slot {
        ResourceDefinition definition;
        Object value;
        boolean used;
        long lastUsedExecution;
        int width;
        int height;
    }
    /** Fluent access declaration returned by addPass; configure before compile. */
    public static final class PassBuilder {
        final FrameGraph graph;
        final String name;
        final int index;
        final FrameGraphPass callback;
        final Set<Integer> reads = new LinkedHashSet<>(), writes = new LinkedHashSet<>();
        final Set<Integer> dependencies = new LinkedHashSet<>();
        final Set<Integer> orderingDependencies = new LinkedHashSet<>();
        boolean sideEffect;
        final Map<Integer, ResourceAccess> readAccess = new LinkedHashMap<>();
        final Map<Integer, ResourceAccess> writeAccess = new LinkedHashMap<>();
        private void checkActive() {
            graph.mutable();
            if (index >= graph.passes.size() || graph.passes.get(index) != this) {
                throw new IllegalStateException("Pass belongs to a previous frame");
            }
        }
        PassBuilder(FrameGraph graph, String name, FrameGraphPass callback, int index) {
            this.graph = graph;
            this.name = name;
            this.callback = callback;
            this.index = index;
        }
        /** @param handle input resource @return this declaration */
        public PassBuilder read(ResourceHandle<?> handle) {
            checkActive();
            graph.check(handle);
            reads.add(handle.index);
            readAccess.putIfAbsent(handle.index, ResourceAccess.GENERAL);
            return this;
        }
        /**
         * Declares the only writer of a logical output. Writing an imported object
         * is externally visible and keeps this pass alive automatically.
         * <pre>{@code
         * // write 声明的是“哪个 Pass 产生这个版本”，不是一次 draw call。
         * // 一个 Pass 内可以绘制很多物体，仍只需一次 write(color)。
         * graph.addPass("Opaque", context -> drawAllOpaque(context.get(color))).write(color);
         * // 另一个 Pass 继续写同一存储时，声明下一个版本：
         * ResourceHandle<RenderTarget> next = graph.nextVersion("AfterTransparent", color);
         * graph.addPass("Transparent", context -> drawTransparent(context.get(next))).write(next);
         * // 错误：第二个 Pass 也 write(color)，即使 addPass 写在后面或 after(opaque)，
         * // 仍违反单版本单写者规则；after 不会创建版本，也不能替代 read/write。
         * // 首次写 color 不需要 nextVersion；每个独立资源都可以有自己的首次写者。
         * }</pre>
         * @param handle output resource
         * @return this declaration
         */
        public PassBuilder write(ResourceHandle<?> handle) {
            checkActive();
            graph.check(handle);
            writes.add(handle.index);
            writeAccess.putIfAbsent(handle.index, ResourceAccess.GENERAL);
            return this;
        }
        /**
         * Declares a GPU read with an explicit consumption type. Multiple different
         * read intents on one handle conservatively become GENERAL.
         * <pre>{@code
         * // compute 写入的图像随后作为纹理读取，屏障由图在本 Pass 前插入。
         * graph.addPass("Sample", context -> draw())
         *         .read(image, ResourceAccess.SAMPLED_TEXTURE).sideEffect();
         * }</pre>
         * @param handle input handle
         * @param access actual GPU usage
         * @return this declaration
         */
        public PassBuilder read(ResourceHandle<?> handle, ResourceAccess access) {
            Objects.requireNonNull(access);
            ResourceAccess previous = readAccess.get(handle.index);
            read(handle);
            readAccess.put(handle.index, previous == null || previous == access ? access : ResourceAccess.GENERAL);
            return this;
        }

        /**
         * Declares a write. STORAGE_IMAGE/STORAGE_BUFFER identify incoherent shader
         * output; default write(handle) cannot infer such writes from callback code.
         * <pre>{@code
         * // 第一步：声明 shader 存储写入，而不是普通 framebuffer 写入。
         * graph.addPass("Cull", context -> dispatch())
         *         .write(lightIndices, ResourceAccess.STORAGE_BUFFER);
         * // 第二步：后续 Pass 声明 read(lightIndices, STORAGE_BUFFER) 后自动同步。
         * }</pre>
         * @param handle output handle
         * @param access actual GPU usage
         * @return this declaration
         */
        public PassBuilder write(ResourceHandle<?> handle, ResourceAccess access) {
            Objects.requireNonNull(access);
            ResourceAccess previous = writeAccess.get(handle.index);
            if (previous != null && previous != access) {
                throw new IllegalArgumentException("Conflicting write usages; declare one explicit write intent");
            }
            write(handle);
            writeAccess.put(handle.index, access);
            return this;
        }

        /**
         * Adds a non-resource dependency (for example a queue mutation).
         * @param prior pass which must execute before this one
         * @return this declaration
         */
        public PassBuilder after(PassBuilder prior) {
            checkActive();
            if (prior == null || prior.graph != graph || prior == this) {
                throw new IllegalArgumentException("Invalid pass dependency");
            }
            prior.checkActive();
            dependencies.add(prior.index);
            return this;
        }
        /** @return this declaration, retained even without an exported output */
        public PassBuilder sideEffect() {
            checkActive();
            sideEffect = true;
            return this;
        }
    }
    private final List<Resource> resources = new ArrayList<>();
    private final List<PassBuilder> passes = new ArrayList<>();
    private final List<Slot> pool = new ArrayList<>();
    private final IdentityHashMap<Object, ResourceHandle<?>> importedIdentities = new IdentityHashMap<>();
    private final Map<String, ResourceHandle<?>> importedNames = new LinkedHashMap<>();
    private final IdentityHashMap<Object, Integer> shaderWrites = new IdentityHashMap<>();
    private final Map<String, Persistent> persistentResources = new LinkedHashMap<>();
    private final FrameGraphContext context = new FrameGraphContext(this);
    private List<PassBuilder> schedule;
    private final List<String> executedPasses = new ArrayList<>();
    private boolean executing, closed, outputValid;
    private boolean expanding;
    private boolean expanded;
    private String prefix = "";
    private long executionCount;
    private long allocationCount;
    private long reuseCount;
    private int width = -1, height = -1;
    private RenderManager ownerManager;
    private ViewPort ownerViewport;

    private void mutable() {
        if (closed || (executing || schedule != null) && !expanding) {
            throw new IllegalStateException("Graph is closed, executing or compiled");
        }
    }
    void check(ResourceHandle<?> handle) {
        if (handle == null || handle.owner != this || handle.index >= resources.size()
            || resources.get(handle.index).handle != handle) {
            throw new IllegalArgumentException("Resource belongs to another graph or previous frame");
        }
    }
    /**
     * Starts a new declaration frame. Drops passes/handles/exports, retaining the
     * transient allocation pool and named persistent resources. Old handles and
     * PassBuilders must never be reused. Render-thread only, outside execution.
     * Static graphs do not need this call.
     * <pre>{@code
     * // 第一步：每帧重建逻辑图，但保留底层资源池。
     * graph.beginFrame();
     * // 第二步：按本帧需求声明资源；同名且定义相同可再次获取。
     * ResourceHandle<RenderTarget> hdr = graph.findOrCreate("HDR", definition);
     * // 第三步：添加本帧 Pass，声明 read/write；然后 compile/execute。
     * // 不要把 hdr 保存到下一帧，下一帧应重新获取句柄。
     * }</pre>
     */
    public void beginFrame() {
        if (closed || executing) {
            throw new IllegalStateException("Cannot rebuild closed or executing graph");
        }
        releaseAll();
        resources.clear();
        importedIdentities.clear();
        importedNames.clear();
        passes.clear();
        executedPasses.clear();
        schedule = null;
        expanded = false;
        prefix = "";
    }

    /**
     * Rebuilds a frame and invalidates size-dependent allocations before history
     * validity is queried by the builder. Prefer this overload for dynamic graphs.
     * <pre>{@code
     * // 每帧第一步：先处理尺寸变化，再检查历史资源是否需要初始化。
     * graph.beginFrame(camera.getWidth(), camera.getHeight());
     * }</pre>
     * @param width current viewport width
     * @param height current viewport height
     */
    public void beginFrame(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport must have positive dimensions");
        }
        beginFrame();
        if (this.width != width || this.height != height) {
            clearResources();
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Finds a handle declared in this frame, or returns null. This searches logical
     * declarations, not the physical pool. A wrong runtime type is an error.
     * @param name logical name
     * @param type required resource type
     * @param <T> resource type
     * @return current handle, or null
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> findResource(String name, Class<T> type) {
        Objects.requireNonNull(type);
        ResourceHandle<?> alias = importedNames.get(prefix + name);
        if (alias != null) {
            if (alias.type != type) {
                throw new IllegalArgumentException("Resource type mismatch: " + name);
            }
            return (ResourceHandle<T>) alias;
        }
        for (Resource r : resources) {
            if (r.handle.getName().equals(prefix + name)) {
                if (r.handle.type != type) {
                    throw new IllegalArgumentException("Resource type mismatch: " + name);
                }
                return (ResourceHandle<T>) r.handle;
            }
        }
        return null;
    }

    /**
     * Gets/declares a transient resource in the current frame. Repeated calls must
     * use equal definitions. Contents are undefined until written, even if the
     * physical allocation was reused from a previous frame.
     * @param name current-frame name
     * @param definition allocation contract
     * @param <T> resource type
     * @return current-frame handle
     */
    public <T> ResourceHandle<T> findOrCreate(String name, ResourceDefinition<T> definition) {
        mutable();
        ResourceHandle<T> existing = findResource(name, definition.getType());
        if (existing == null) {
            return createResource(name, definition);
        }
        Resource r = resources.get(existing.index);
        if (r.imported != null || r.persistent != null || !definition.equals(r.definition)) {
            throw new IllegalArgumentException("Resource definition/lifetime mismatch: " + name);
        }
        return existing;
    }

    /**
     * Gets/declares an owned persistent resource. Its allocation and contents are
     * retained across beginFrame(), never shared with transient resources. First
     * use and use after resize/clearResources require a writing pass. Reads from
     * uninitialized history fail; use isPersistentResourceValid to schedule a
     * producer. Use two names for history ping-pong, not texture feedback.
     * Equal names require equal definitions. clearResources/close disposes them.
     * <pre>{@code
     * // 第一步：每帧重新获取句柄，历史内容由图持有。
     * ResourceHandle<RenderTarget> history =
     *         graph.findOrCreatePersistentResource("History", definition);
     * // 第二步：首次使用或尺寸变化后，先安排写入 Pass 初始化它。
     * if (!graph.isPersistentResourceValid("History")) {
     *     graph.addPass("Initialize", context -> {
     *         renderer.setFrameBuffer(context.get(history).getFrameBuffer());
     *         renderer.clearBuffers(true, true, true);
     *     }).write(history);
     * }
     * // 第三步：消费者必须声明 read(history)，才可以 context.get(history)。
     * }</pre>
     * @param name stable name across frames
     * @param definition immutable allocation contract
     * @param <T> resource type
     * @return current-frame handle
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> findOrCreatePersistentResource(
        String name, ResourceDefinition<T> definition) {
        mutable();
        Objects.requireNonNull(definition);
        ResourceHandle<T> existing = findResource(name, definition.getType());
        Persistent persistent = persistentResources.get(prefix + name);
        if (existing != null) {
            Resource r = resources.get(existing.index);
            if (r.persistent == null || !definition.equals(r.definition)) {
                throw new IllegalArgumentException("Resource definition/lifetime mismatch: " + name);
            }
            return existing;
        }
        if (persistent != null && (persistent.definition.getType() != definition.getType()
            || !definition.equals(persistent.definition))) {
            throw new IllegalArgumentException("Persistent definition mismatch: " + name);
        }
        Resource r = add(name, definition.getType());
        if (persistent == null) {
            persistent = new Persistent();
            persistent.definition = definition;
            persistentResources.put(prefix + name, persistent);
        }
        r.definition = definition;
        r.persistent = persistent;
        return (ResourceHandle<T>) r.handle;
    }

    /**
     * @param name persistent resource name
     * @return whether a previous successful writer initialized the allocation;
     * false after resize, failure, clearResources, or before first use
     */
    public boolean isPersistentResourceValid(String name) {
        Persistent persistent = persistentResources.get(prefix + name);
        return persistent != null && persistent.valid
            && (!executing || persistent.width == context.viewport.getCamera().getWidth()
                && persistent.height == context.viewport.getCamera().getHeight());
    }
    /**
     * Invalidates history contents without disposing the allocation. Call on the
     * render thread outside execution, before deciding which history passes to build.
     * Existing exported access to this resource is revoked. Other histories survive.
     * <pre>{@code
     * // 第一步：摄像机瞬移后，在下一帧建图前废弃旧历史，但不释放显存。
     * graph.invalidatePersistentResource("TaaHistory");
     * // 第二步：本帧建图时检查有效性，安排写入后再读取。
     * if (!graph.isPersistentResourceValid("TaaHistory")) {
     *     // 添加初始化 Pass；write(history) 本身不会清空纹理。
     * }
     * }</pre>
     * @param name persistent name, including its scope when called outside that scope
     * @return true if registered, false if absent
     */
    public boolean invalidatePersistentResource(String name) {
        requireMaintenance();
        Persistent persistent = persistentResources.get(prefix + Objects.requireNonNull(name));
        if (persistent == null) {
            return false;
        }
        persistent.valid = false;
        for (Resource resource : resources) {
            if (resource.persistent == persistent) {
                release(resource);
            }
        }
        return true;
    }

    /**
     * Disposes one persistent allocation and removes its name/definition reservation.
     * Call after beginFrame and before redeclaring this name; a current-frame handle
     * prevents removal. Failure to destroy leaves the registration available for retry.
     * <pre>{@code
     * // 第一步：仅限自己拥有的图，在渲染线程开始新一轮声明。
     * graph.beginFrame(width, height);
     * // 第二步：效果关闭时释放它的历史，不影响其他效果。
     * graph.removePersistentResource("History");
     * // 第三步（若改配置后重建）：此时允许同名使用新的格式/尺寸定义。
     * ResourceHandle<RenderTarget> history =
     *         graph.findOrCreatePersistentResource("History", newDefinition);
     * // 第四步：新历史无效，必须安排初始化写入。
     * // 引擎拥有的主图不要自行 beginFrame；在建图回调首次声明前移除即可。
     * }</pre>
     * @param name persistent resource name
     * @return true if removed, false if absent
     */
    public boolean removePersistentResource(String name) {
        mutable();
        String fullName = prefix + Objects.requireNonNull(name);
        Persistent persistent = persistentResources.get(fullName);
        if (persistent == null) {
            return false;
        }
        for (Resource resource : resources) {
            if (resource.persistent == persistent) {
                throw new IllegalStateException("Persistent resource already declared: " + fullName);
            }
        }
        if (persistent.value != null) {
            persistent.definition.destroy(persistent.value);
        }
        persistentResources.remove(fullName);
        return true;
    }

    private void requireMaintenance() {
        if (closed || executing) {
            throw new IllegalStateException("Resource maintenance requires an open, idle graph");
        }
    }

    /**
     * Releases only unused transient allocations, never persistent/imported resources
     * or retained exports. Age counts execute calls, not beginFrame calls or seconds.
     * No automatic trimming is enabled: callers choose the retention policy.
     * Call on the render thread with a current graphics context, outside execution.
     * <pre>{@code
     * // 第一步：渲染后按需回收最近 120 次图执行都未使用的临时分配。
     * int released = graph.trimUnusedResources(120);
     * // 第二步：内存紧张时传 0，立即回收所有未占用的临时分配。
     * // 导出资源仍被保留；下一次 beginFrame/execute 释放导出后才可回收。
     * graph.trimUnusedResources(0);
     * // 不要每帧无条件传 0，否则会失去资源池复用带来的好处。
     * }</pre>
     * @param unusedExecutions minimum number of executions since last use, zero for all idle slots
     * @return number of physical allocations released
     */
    public int trimUnusedResources(long unusedExecutions) {
        requireMaintenance();
        if (unusedExecutions < 0) {
            throw new IllegalArgumentException("Negative idle execution count");
        }
        int removed = 0;
        Iterator<Slot> iterator = pool.iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next();
            if (!slot.used && executionCount - slot.lastUsedExecution >= unusedExecutions) {
                slot.definition.destroy(slot.value);
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Immutable allocation counters; counts objects, not estimated GPU bytes. */
    public static final class PoolStats {
        /** Current pooled physical objects, including retained exports. */
        public final int transientAllocations;
        /** Physical objects still retained by live handles (normally exports after execution). */
        public final int retainedTransientAllocations;
        /** Allocated persistent objects; excludes registered but not yet allocated histories. */
        public final int persistentAllocations;
        /** Cumulative physical transient creations over this graph instance lifetime. */
        public final long totalTransientAllocations;
        /** Cumulative acquisitions satisfied by an existing free physical allocation. */
        public final long transientReuses;

        private PoolStats(int transientAllocations, int retainedTransientAllocations,
                int persistentAllocations, long totalTransientAllocations, long transientReuses) {
            this.transientAllocations = transientAllocations;
            this.retainedTransientAllocations = retainedTransientAllocations;
            this.persistentAllocations = persistentAllocations;
            this.totalTransientAllocations = totalTransientAllocations;
            this.transientReuses = transientReuses;
        }

        @Override
        public String toString() {
            return "PoolStats{transient=" + transientAllocations + ", retained="
                    + retainedTransientAllocations + ", persistent=" + persistentAllocations
                    + ", allocated=" + totalTransientAllocations + ", reused=" + transientReuses + "}";
        }
    }

    /**
     * Takes a render-thread snapshot without compiling or allocating resources.
     * Cumulative counters are not reset by clearResources; imports are excluded.
     * <pre>{@code
     * // 渲染完成后查看池规模；retained 通常是尚未释放的导出目标。
     * FrameGraph.PoolStats stats = graph.getPoolStats();
     * System.out.println(stats);
     * System.out.println("复用次数=" + stats.transientReuses);
     * }</pre>
     * @return immutable snapshot
     */
    public PoolStats getPoolStats() {
        int retained = 0;
        int persistent = 0;
        for (Slot slot : pool) {
            if (slot.used) {
                retained++;
            }
        }
        for (Persistent entry : persistentResources.values()) {
            if (entry.value != null) {
                persistent++;
            }
        }
        return new PoolStats(pool.size(), retained, persistent, allocationCount, reuseCount);
    }

    /**
     * Describes current logical dependencies and lifetimes. Indices refer to the
     * compiled schedule; -1 means unused. Does not predict future expansion nodes.
     * <pre>{@code
     * // 在帧执行结束后打印，可看到已经展开的节点、读写和资源存活区间。
     * System.out.println(graph.describe());
     * // 仅 compile 后打印则只包含当前已声明的节点；不会提前执行规划回调。
     * }</pre>
     * @return diagnostic text, including culled passes and resource ownership
     */
    public String describe() {
        compile();
        StringBuilder result = new StringBuilder();
        List<PassBuilder> displayed = new ArrayList<>(schedule);
        for (PassBuilder pass : passes) {
            if (!schedule.contains(pass)) {
                displayed.add(pass);
            }
        }
        for (PassBuilder pass : displayed) {
            result.append(schedule.contains(pass) ? "LIVE " : "CULLED ").append(pass.name);
            result.append(" read=").append(resourceNames(pass.reads));
            result.append(" write=").append(resourceNames(pass.writes));
            result.append(" readUsage=").append(pass.readAccess.values());
            result.append(" writeUsage=").append(pass.writeAccess.values());
            result.append(" after=").append(passNames(pass.dependencies));
            result.append(" orderedAfter=").append(passNames(pass.orderingDependencies));
            result.append(" sideEffect=").append(pass.sideEffect).append('\n');
        }
        for (Resource resource : resources) {
            result.append(resource.handle.getName()).append(" kind=");
            result.append(resource.imported != null ? "imported"
                    : resource.persistent != null ? "persistent" : "transient");
            result.append(" lifetime=[").append(resource.first).append(',').append(resource.last);
            result.append("] exported=").append(resource.exported);
            result.append(" storage=").append(resource.storage.handle.getName());
            if (resource.previous != null) {
                result.append(" previous=").append(resource.previous.handle.getName());
            }
            result.append('\n');
        }
        for (Map.Entry<String, ResourceHandle<?>> alias : importedNames.entrySet()) {
            result.append("ALIAS ").append(alias.getKey()).append(" -> ");
            result.append(alias.getValue().getName()).append('\n');
        }
        return result.append(getPoolStats()).toString();
    }

    private List<String> passNames(Set<Integer> ids) {
        List<String> names = new ArrayList<>();
        for (int id : ids) {
            names.add(passes.get(id).name);
        }
        return names;
    }

    private List<String> resourceNames(Set<Integer> ids) {
        List<String> names = new ArrayList<>();
        for (int id : ids) {
            names.add(resources.get(id).handle.getName());
        }
        return names;
    }

    private <T> Resource add(String name, Class<T> type) {
        mutable();
        Objects.requireNonNull(name);
        name = prefix + name;
        Objects.requireNonNull(type);
        if (importedNames.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate imported alias: " + name);
        }
        for (Resource r : resources) {
            if (r.handle.getName().equals(name)) {
                throw new IllegalArgumentException("Duplicate resource: " + name);
            }
        }
        Resource r = new Resource();
        r.storage = r;
        r.handle = new ResourceHandle<T>(this, resources.size(), name, type);
        resources.add(r);
        return r;
    }
    /**
     * Declares a graph-owned transient resource, allocated only if a live pass needs it.
     * @param name unique debug name
     * @param definition immutable allocation/pooling contract
     * @param <T> resource type
     * @return typed logical handle
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> createResource(String name, ResourceDefinition<T> definition) {
        Objects.requireNonNull(definition);
        if (persistentResources.containsKey(prefix + name)) {
            throw new IllegalArgumentException("Name reserved for persistent resource: " + name);
        }
        Resource r = add(name, definition.getType());
        r.definition = definition;
        return (ResourceHandle<T>) r.handle;
    }
    /**
     * Borrows a persistent external object, e.g. a viewport framebuffer or history
     * texture. Supplier may return null, and is called once when first accessed in
     * each execution. The caller owns its lifecycle and resize handling.
     * @param name unique name
     * @param type runtime type
     * @param supplier render-thread source of the current object
     * @param <T> resource type
     * @return imported handle
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> importResource(
        String name, Class<T> type, Supplier<? extends T> supplier) {
        Objects.requireNonNull(supplier);
        if (persistentResources.containsKey(prefix + name)) {
            throw new IllegalArgumentException("Name reserved for persistent resource: " + name);
        }
        Resource r = add(name, type);
        r.imported = supplier;
        return (ResourceHandle<T>) r.handle;
    }
    /**
     * Imports a resource with an explicit physical identity token. Identity is
     * compared by reference, not equals(). Repeated tokens return the SAME canonical
     * handle; only the first supplier is used. Aliases cannot hide multiple writers.
     * Use nextVersion for sequential writes. Tokens are frame-local registrations.
     * <pre>{@code
     * // 第一步：模块共享同一个稳定 token（也可直接用实际纹理/帧缓冲对象）。
     * ResourceHandle<FrameBuffer> scene = graph.importResource(
     *         "Scene", FrameBuffer.class, outputIdentity, viewPort::getOutputFrameBuffer);
     * // 第二步：另一模块导入相同存储时使用同一个 token，不能各自 new Object。
     * ResourceHandle<FrameBuffer> alias = graph.importResource(
     *         "PostInput", FrameBuffer.class, outputIdentity, viewPort::getOutputFrameBuffer);
     * // scene == alias；read(alias) 会依赖 write(scene)。默认帧缓冲也用非 null token。
     * // token 必须代表同一整个资源，不要为同一纹理的不同包装对象分配不同 token。
     * }</pre>
     * @param name local alias name
     * @param type resource type
     * @param identity non-null physical-storage identity token
     * @param supplier current object, sampled once per execution for this identity
     * @param <T> resource type
     * @return canonical imported handle
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> importResource(String name, Class<T> type,
            Object identity, Supplier<? extends T> supplier) {
        mutable();
        Objects.requireNonNull(identity);
        Objects.requireNonNull(type);
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(name);
        ResourceHandle<?> existing = importedIdentities.get(identity);
        if (existing != null) {
            if (existing.type != type) {
                throw new IllegalArgumentException("Imported identity type mismatch: " + name);
            }
            ResourceHandle<T> named = findResource(name, type);
            if (named != null && named != existing || persistentResources.containsKey(prefix + name)) {
                throw new IllegalArgumentException("Imported alias name collision: " + name);
            }
            importedNames.put(prefix + name, existing);
            return (ResourceHandle<T>) existing;
        }
        ResourceHandle<T> handle = importResource(name, type, supplier);
        importedIdentities.put(identity, handle);
        return handle;
    }

    /**
     * Declares the next logical version of the SAME physical storage. No copy or
     * allocation is performed. Versions form a linear chain; old readers finish
     * before the new writer. Contents are preserved unless the callback overwrites
     * them. Each version still has at most one writer. Only the final version may
     * be exported, since old contents no longer exist after an in-place update.
     *
     * <p>Why versions: a read must identify WHICH contents it consumes, not merely
     * which physical allocation. read(previous) must finish before the next writer;
     * read(next) requires that writer to finish first. Declaration order alone cannot
     * express this distinction without an additional implicit "latest version" rule.
     * This API chooses explicit versions so independently built modules can name
     * their inputs unambiguously. This is a design choice, not an OpenGL requirement.
     * Version edges, resource reads/writes and after() determine scheduling together;
     * nextVersion alone neither creates a pass nor submits any GPU command.</p>
     *
     * <p>Related design: Filament's FrameGraph also separates resource versions from
     * physical storage and returns the resulting handle from builder.write(). This
     * implementation instead keeps write() fluent on PassBuilder and exposes
     * nextVersion() separately. The APIs and scheduling policies are not identical.
     * See <a href="https://google.github.io/filament/notes/framegraph.html">Filament
     * FrameGraph notes</a> and its FrameGraph.cpp write/createNewVersion implementation.</p>
     * <pre>{@code
     * // 可以把资源理解为一块白板：old 是覆盖前的内容，next 是覆盖后的内容。
     * // 两个句柄指向同一块白板，nextVersion 本身既不画图，也不复制或清空。
     * // FG 排序：写 old -> 所有读 old 的 Pass -> 写 next -> 读 next。
     * // 必须由后续 Pass 的 write(next) 实际改变内容；不是跨帧保存旧版本的快照。
     * // 第一步：不透明绘制写入初始颜色版本。
     * ResourceHandle<RenderTarget> opaque = graph.createResource("OpaqueColor", definition);
     * graph.addPass("Opaque", context -> drawOpaque(context.get(opaque))).write(opaque);
     * // 第二步：透明绘制继续写同一个目标，保留颜色/深度（不要再次清空）。
     * ResourceHandle<RenderTarget> transparent = graph.nextVersion("TransparentColor", opaque);
     * graph.addPass("Transparent", context -> blend(context.get(transparent)))
     *         .write(transparent, ResourceAccess.FRAMEBUFFER);
     * // 第三步：即使旧版本读者在代码里后添加，也必须排在 Transparent 覆盖之前。
     * graph.addPass("InspectOpaque", context -> inspect(context.get(opaque)))
     *         .read(opaque).sideEffect();
     * // 实际顺序是 Opaque -> InspectOpaque -> Transparent，不能按代码行顺序理解。
     * // 若要检查透明绘制后的结果，应 read(transparent)，不能仍 read(opaque)。
     * // 第四步：后处理读取新版本；旧版本的读者会自动排在 Transparent 之前。
     * // framebuffer 混合可以这样做；采样旧颜色同时写同一纹理仍是非法反馈。
     * // 需要同时保存两张图或采样旧图写新图时，使用 createResource 独立目标。
     * 更简便的理解:</n>
     * // A：写入蓝色。
     * graph.addPass("A", ...).write(color);</n>
     *
     * // B：继续覆盖同一目标，改成绿色。</n>
     * ResourceHandle<RenderTarget> next =</n>
     *         graph.nextVersion("NextColor", color);</n>
     *</n>
     * graph.addPass("B", ...).write(next);</n>
     *</n>
     * // C：虽然最后添加，但要求读取“修改前”的蓝色。</n>
     * graph.addPass("C", ...).read(color).sideEffect();</n>
     * A 写蓝色 → C 读蓝色 → B 改成绿色</n>
     * }</pre>
     * @param name unique logical version name
     * @param previous immediate predecessor; cannot branch
     * @param <T> resource type
     * @return new handle sharing storage with previous
     */
    @SuppressWarnings("unchecked")
    public <T> ResourceHandle<T> nextVersion(String name, ResourceHandle<T> previous) {
        mutable();
        check(previous);
        Resource prior = resources.get(previous.index);
        if (persistentResources.containsKey(prefix + name)) {
            throw new IllegalArgumentException("Name reserved for persistent resource: " + name);
        }
        if (prior.next != null || prior.exported) {
            throw new IllegalArgumentException("Cannot branch or overwrite an exported version: " + previous);
        }
        Resource version = add(name, previous.type);
        version.previous = prior;
        prior.next = version;
        version.storage = prior.storage;
        version.definition = prior.definition;
        version.imported = prior.imported;
        version.persistent = prior.persistent;
        return (ResourceHandle<T>) version.handle;
    }

    /**
     * Keeps a final resource and its producer alive until the next execute or
     * clearResources. Read it after successful execution with getExported.
     * @param handle output to retain
     */
    public void exportResource(ResourceHandle<?> handle) {
        mutable();
        check(handle);
        if (resources.get(handle.index).next != null) {
            throw new IllegalArgumentException("Only the final resource version can be exported");
        }
        resources.get(handle.index).exported = true;
    }
    /**
     * Adds a pass; declaration order only breaks ties between currently ready passes.
     * Resource read/write dependencies, version chains and explicit after() constraints
     * take precedence. A declaration does not execute its callback immediately.
     * Expansion groups can add ordering constraints of their own; this method does
     * not remove those constraints. Cyclic or contradictory ordering is an error.
     * <pre>{@code
     * // 第一步：声明资源；声明顺序不等于实际 GPU 执行顺序。
     * ResourceHandle<RenderTarget> color = graph.createResource("Color", definition);
     * // 第二步：故意先添加读者，后添加写者。
     * graph.addPass("Read", context -> inspect(context.get(color))).read(color).sideEffect();
     * graph.addPass("Write", context -> draw(context.get(color))).write(color);
     * // compile 后仍是 Write -> Read，因为读者依赖该版本的生产者。
     * // 第三步：若是继续覆盖同一目标，用 nextVersion 区分修改前后的内容。
     * // 若只需约束 CPU 回调/队列操作顺序，用 second.after(first)。
     * // 不存在任何依赖时，多个就绪 Pass 才按 addPass 先后顺序稳定选择。
     * // 不要靠“恰好先添加”来表达必须存在的依赖。
     * }</pre>
     * @param name unique debug name
     * @param callback body invoked on the render thread
     * @return access declaration; chain read/write/after/sideEffect before compile
     */
    public PassBuilder addPass(String name, FrameGraphPass callback) {
        mutable();
        Objects.requireNonNull(name);
        name = prefix + name;
        Objects.requireNonNull(callback);
        for (PassBuilder p : passes) {
            if (p.name.equals(name)) {
                throw new IllegalArgumentException("Duplicate pass: " + name);
            }
        }
        PassBuilder p = new PassBuilder(this, name, callback, passes.size());
        passes.add(p);
        return p;
    }
    /**
     * Adds a CPU planning point that expands ordered passes into THIS graph when
     * reached. No child graph is created/executed. The planner may only declare
     * work, not draw. Names are prefixed with the planning point's full name.
     * All appended passes execute before previously pending passes. Dependencies
     * and lifetimes are recompiled by the same scheduler after expansion.
     * Rebuild with beginFrame before the next execution. Cross-expansion reads of
     * expired transient handles are rejected; use imported/persistent/exported
     * resources for values that must cross a planning boundary.
     * <pre>{@code
     * // 第一步：先准备本帧队列；规划节点在队列就绪后执行。
     * FrameGraph.PassBuilder queue = graph.addPass("Queue", context -> prepareQueue()).sideEffect();
     * // 第二步：把实际绘制节点添加到同一个图，不创建/执行子图。
     * graph.addExpansionPass("Lighting", frame -> {
     *     frame.addPass("Draw", context -> drawLights()).sideEffect();
     * }).after(queue);
     * // 第三步：渲染完成后，getExecutionOrder() 包含 Lighting/Draw。
     * }</pre>
     * @param name planning stage name
     * @param planner render-thread declaration callback
     * @return ordering declaration
     */
    public PassBuilder addExpansionPass(String name, Consumer<FrameGraph> planner) {
        return expand(name, planner, true);
    }

    /**
     * Adds a planning boundary whose declared passes retain normal dead-pass
     * elimination. Declaration order is preserved as an ordering constraint, not
     * as a reason to retain unused work. Unlike addExpansionPass, callbacks with
     * observable CPU effects must explicitly declare sideEffect(). Imports,
     * persistent writes and exports remain roots. No parallel execution is added.
     * The same transient-boundary and beginFrame restrictions apply.
     * <pre>{@code
     * // 第一步：在资源依赖完整的新模块中使用可裁剪规划点。
     * graph.addResourceExpansionPass("OptionalEffect", planned -> {
     *     ResourceHandle<RenderTarget> scratch =
     *             planned.createResource("Scratch", definition);
     *     planned.addPass("Generate", context -> {
     *         // 在这里真正绘制并初始化 scratch。
     *     }).write(scratch);
     *     // 第二步：若没有消费者，Generate 会被裁剪；不会分配 scratch。
     *     // 需要输出时将它交给本规划区域的后续读者，或 exportResource(scratch)。
     *     // 第三步：日志、检查等不能被省略的行为，必须显式 sideEffect。
     *     planned.addPass("Check", context -> checkState()).sideEffect();
     * });
     * // 旧模块依赖隐含状态时继续用 addExpansionPass，默认行为不变。
     * }</pre>
     * @param name planning stage name
     * @param planner declaration-only callback
     * @return planning node declaration
     */
    public PassBuilder addResourceExpansionPass(String name, Consumer<FrameGraph> planner) {
        return expand(name, planner, false);
    }

    private PassBuilder expand(String name, Consumer<FrameGraph> planner, boolean retainAll) {
        Objects.requireNonNull(planner);
        String groupName = prefix + name;
        return addPass(name, ignored -> {
            int oldPassCount = passes.size();
            int oldResourceCount = resources.size();
            int current = schedule.indexOf(context.pass);
            List<PassBuilder> completed = new ArrayList<>(schedule.subList(0, current + 1));
            List<PassBuilder> pending = new ArrayList<>(schedule.subList(current + 1, schedule.size()));
            expanding = true;
            String oldPrefix = prefix;
            prefix = groupName + "/";
            try {
                planner.accept(this);
                PassBuilder prior = context.pass;
                for (int i = oldPassCount; i < passes.size(); i++) {
                    PassBuilder pass = passes.get(i);
                    if (retainAll) {
                        pass.dependencies.add(prior.index);
                        pass.sideEffect = true;
                    } else {
                        pass.orderingDependencies.add(prior.index);
                    }
                    for (int id : pass.reads) {
                        Resource resource = resources.get(id);
                        if (id < oldResourceCount && resource.imported == null
                            && resource.persistent == null && !resource.exported) {
                            throw new IllegalStateException("Transient crosses planning boundary: " + resource.handle);
                        }
                    }
                    prior = pass;
                }
                if (prior != context.pass) {
                    for (PassBuilder pass : pending) {
                        if (retainAll) {
                            pass.dependencies.add(prior.index);
                        } else {
                            pass.orderingDependencies.add(prior.index);
                        }
                    }
                }
                schedule = null;
                compile();
                if (schedule.size() < completed.size()
                    || !schedule.subList(0, completed.size()).equals(completed)) {
                    throw new IllegalStateException("Planning changed already executed pass order");
                }
                expanded = true;
            } finally {
                prefix = oldPrefix;
                expanding = false;
            }
        }).sideEffect();
    }

    /**
     * Validates writers, uninitialized reads and cycles, then freezes the graph.
     * Unused passes are culled after validation. No GPU resources are allocated.
     * @throws IllegalStateException on invalid dependency declarations
     */
    public void compile() {
        if (closed || executing && !expanding) {
            throw new IllegalStateException("Graph unavailable");
        }
        if (schedule != null) {
            return;
        }
        for (Resource r : resources) {
            r.writer = -1;
            r.first = -1;
            r.last = -1;
        }
        List<Set<Integer>> deps = new ArrayList<>();
        for (PassBuilder p : passes) {
            deps.add(new LinkedHashSet<>(p.dependencies));
            for (int i : p.writes) {
                Resource r = resources.get(i);
                if (r.writer >= 0) {
                    throw new IllegalStateException("Multiple writers: " + r.handle);
                }
                r.writer = p.index;
            }
        }
        for (PassBuilder p : passes) {
            for (int i : p.reads) {
                Resource r = resources.get(i);
                if (r.writer < 0 && r.imported == null && r.persistent == null) {
                    throw new IllegalStateException(
                        "Uninitialized read: " + r.handle + " in " + p.name);
                }
                if (r.writer == p.index && r.imported == null) {
                    throw new IllegalStateException("Transient read/write feedback: " + r.handle);
                }
                if (r.writer >= 0 && r.writer != p.index) {
                    deps.get(p.index).add(r.writer);
                }
            }
        }
        for (Resource version : resources) {
            if (version.previous != null && version.writer < 0) {
                throw new IllegalStateException("Version requires a writer: " + version.handle);
            }
            if (version.previous == null || version.writer < 0) {
                continue;
            }
            Resource prior = version.previous;
            if (prior.writer < 0 && prior.imported == null && prior.persistent == null) {
                throw new IllegalStateException("Uninitialized previous version: " + prior.handle);
            }
            if (prior.writer >= 0) {
                deps.get(version.writer).add(prior.writer);
            }
            for (PassBuilder reader : passes) {
                if (reader.index != version.writer && reader.reads.contains(prior.handle.index)) {
                    // Anti-dependency orders readers but does not keep unused readers alive.
                    passes.get(version.writer).orderingDependencies.add(reader.index);
                }
            }
        }
        for (PassBuilder pass : passes) {
            for (int read : pass.reads) {
                for (int write : pass.writes) {
                    Resource source = resources.get(read);
                    Resource target = resources.get(write);
                    ResourceAccess intent = pass.readAccess.get(read);
                    boolean storageUpdate = intent.shaderStorage
                            && intent == pass.writeAccess.get(write);
                    if (source.storage == target.storage
                            && (read != write || intent == ResourceAccess.SAMPLED_TEXTURE)
                            && intent != ResourceAccess.FRAMEBUFFER && !storageUpdate) {
                        throw new IllegalStateException("In-place sampling feedback: " + pass.name);
                    }
                }
            }
        }
        List<Integer> sorted = new ArrayList<>();
        Set<Integer> done = new HashSet<>();
        while (sorted.size() < passes.size()) {
            int next = -1;
            for (int i = 0; i < passes.size(); i++) {
                if (!done.contains(i) && done.containsAll(deps.get(i))
                        && done.containsAll(passes.get(i).orderingDependencies)) {
                    next = i;
                    break;
                }
            }
            if (next < 0) {
                throw new IllegalStateException("FrameGraph dependency cycle");
            }
            sorted.add(next);
            done.add(next);
        }
        Set<Integer> live = new HashSet<>();
        for (PassBuilder p : passes) {
            if (p.sideEffect) {
                mark(p.index, deps, live);
            }
        }
        for (Resource r : resources) {
            if (r.exported && r.writer < 0 && r.imported == null && r.persistent == null) {
                throw new IllegalStateException("Uninitialized export: " + r.handle);
            }
            if (r.writer >= 0 && (r.exported || r.imported != null || r.persistent != null)) {
                mark(r.writer, deps, live);
            }
        }
        List<PassBuilder> result = new ArrayList<>();
        for (int i : sorted) {
            if (live.contains(i)) {
                result.add(passes.get(i));
            }
        }
        for (int i = 0; i < result.size(); i++) {
            PassBuilder p = result.get(i);
            Set<Integer> access = new HashSet<>(p.reads);
            access.addAll(p.writes);
            for (int id : access) {
                Resource r = resources.get(id);
                if (r.first < 0) {
                    r.first = i;
                }
                r.last = i;
            }
        }
        for (Resource resource : resources) {
            if (resource.storage != resource && resource.first >= 0) {
                Resource storage = resource.storage;
                storage.first = storage.first < 0 ? resource.first : Math.min(storage.first, resource.first);
                storage.last = Math.max(storage.last, resource.last);
            }
        }
        schedule = Collections.unmodifiableList(result);
    }
    private void mark(int id, List<Set<Integer>> deps, Set<Integer> live) {
        if (live.add(id)) {
            for (int prior : deps.get(id)) {
                mark(prior, deps, live);
            }
        }
    }
    /** @return immutable execution-order names, compiling if necessary */
    public List<String> getExecutionOrder() {
        compile();
        List<String> names = new ArrayList<>();
        for (PassBuilder p : schedule) {
            names.add(p.name);
        }
        return Collections.unmodifiableList(names);
    }
    /**
     * Returns passes actually entered during the latest execution, including
     * expanded module passes. Unlike getExecutionOrder(), aborted/unreached work
     * is absent. A failed pass is included. Names match GPU debug group suffixes.
     * <pre>{@code
     * // 在 simpleRender 中一次打印实际帧内容（规划节点也会列出）。
     * FrameGraph graph = renderManager.getViewPortFrameGraph(viewPort);
     * for (String pass : graph.getExecutedPasses()) {
     *     System.out.println(pass);
     * }
     * // 用 RenderDoc 启动 LWJGL 应用并截帧，Event Browser 中的同名分组
     * // 对应 ViewPort 名称 + "/" + 上面的 Pass 名称，无需 RenderDoc 专用 SDK。
     * }</pre>
     * @return immutable snapshot of entered pass names
     */
    public List<String> getExecutedPasses() {
        return Collections.unmodifiableList(new ArrayList<>(executedPasses));
    }

    /** @return immutable names of passes eliminated as unused */
    public List<String> getCulledPasses() {
        compile();
        List<String> names = new ArrayList<>();
        for (PassBuilder p : passes) {
            if (!schedule.contains(p)) {
                names.add(p.name);
            }
        }
        return Collections.unmodifiableList(names);
    }
    Object value(int id) {
        Resource r = resources.get(id);
        if (!r.resolved) {
            throw new IllegalStateException("Resource not live: " + r.handle);
        }
        return r.value;
    }
    private boolean isStorageExported(Resource resource) {
        if (resource.exported) {
            return true;
        }
        if (resource.storage == resource) {
            for (Resource version = resource.next; version != null; version = version.next) {
                if (version.exported) {
                    return true;
                }
            }
        }
        return false;
    }

    private void release(Resource r) {
        if (r.slot != null) {
            r.slot.used = false;
        }
        r.slot = null;
        r.value = null;
        r.resolved = false;
    }
    private void releaseAll() {
        for (Resource r : resources) {
            release(r);
            r.importedValue = null;
            r.importedResolved = false;
        }
        outputValid = false;
    }
    @SuppressWarnings("unchecked")
    private void acquire(Resource r) {
        if (r.resolved) {
            return;
        }
        if (r.storage != r) {
            acquire(r.storage);
            r.value = r.storage.value;
            r.resolved = true;
            return;
        }
        if (r.imported != null) {
            if (!r.importedResolved) {
                r.importedValue = r.handle.type.cast(r.imported.get());
                r.importedResolved = true;
            }
            r.value = r.importedValue;
            r.resolved = true;
            return;
        }
        int currentWidth = context.viewport.getCamera().getWidth();
        int currentHeight = context.viewport.getCamera().getHeight();
        if (r.persistent != null) {
            Persistent persistent = r.persistent;
            if (persistent.value != null
                && (persistent.width != currentWidth || persistent.height != currentHeight)) {
                persistent.definition.destroy(persistent.value);
                persistent.value = null;
                persistent.valid = false;
            }
            if (persistent.value == null) {
                persistent.value = Objects.requireNonNull(
                    persistent.definition.create(context.manager.getRenderer(), currentWidth, currentHeight));
                persistent.width = currentWidth;
                persistent.height = currentHeight;
            }
            r.value = r.handle.type.cast(persistent.value);
            r.resolved = true;
            return;
        }
        Slot slot = null;
        for (Slot candidate : pool) {
            if (!candidate.used && candidate.definition.getType() == r.handle.type
                && candidate.width == currentWidth && candidate.height == currentHeight
                && candidate.definition.equals(r.definition)) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) {
            slot = new Slot();
            slot.definition = r.definition;
            slot.value = Objects.requireNonNull(
                r.definition.create(context.manager.getRenderer(), currentWidth, currentHeight),
                "Null allocation");
            slot.width = currentWidth;
            slot.height = currentHeight;
            pool.add(slot);
            allocationCount++;
        } else {
            reuseCount++;
        }
        slot.lastUsedExecution = executionCount;
        r.value = r.handle.type.cast(slot.value);
        r.resolved = true;
        r.slot = slot;
        slot.used = true;
    }
    private void synchronizeAccesses(PassBuilder pass) {
        int barriers = 0;
        Map<Integer, ResourceAccess> accesses = new LinkedHashMap<>(pass.readAccess);
        for (Map.Entry<Integer, ResourceAccess> entry : pass.writeAccess.entrySet()) {
            accesses.merge(entry.getKey(), entry.getValue(), (a, b) -> a == b ? a : ResourceAccess.GENERAL);
        }
        for (Map.Entry<Integer, ResourceAccess> access : accesses.entrySet()) {
            Resource resource = resources.get(access.getKey());
            Integer published = shaderWrites.get(resource.value);
            if (published != null) {
                barriers |= access.getValue().barrierBits & ~published;
            }
        }
        for (ResourceAccess write : pass.writeAccess.values()) {
            if (write.shaderStorage) {
                // Order upcoming shader stores after previous accesses, including old-frame reads.
                barriers |= write.barrierBits;
            }
        }
        if (barriers != 0) {
            context.manager.getRenderer().memoryBarrier(barriers);
            final int emitted = barriers;
            shaderWrites.replaceAll((object, published) -> published | emitted);
        }
        // Mark before entering the callback: failures may happen after submitting GPU work.
        for (Map.Entry<Integer, ResourceAccess> access : pass.writeAccess.entrySet()) {
            if (access.getValue().shaderStorage) {
                shaderWrites.put(resources.get(access.getKey()).value, 0);
            }
        }
    }

    private void publishShaderWrites(RenderManager manager) {
        if (!shaderWrites.isEmpty()) {
            // Exported/imported/history storage may be consumed outside this graph or next frame.
            // Conservative boundary flush also makes pooled-object reuse safe after abort/failure.
            boolean unpublished = shaderWrites.values().stream().anyMatch(mask -> mask != -1);
            if (unpublished) {
                manager.getRenderer().memoryBarrier(-1);
            }
            shaderWrites.clear();
        }
    }

    /**
     * Executes without an explicit queue, useful from a SceneProcessor callback.
     * @param manager owning manager
     * @param viewport owning viewport
     * @return true on success, false when a pass requested abort
     */
    public boolean execute(RenderManager manager, ViewPort viewport) {
        return execute(manager, viewport, null);
    }
    /**
     * Executes the compiled schedule. Resize discards the old pool. Exports from a
     * previous execution expire at entry. Failure/abort clears all live references
     * and keeps reusable allocations; an exception is rethrown with the pass name.
     * @param manager owning manager, with current graphics context
     * @param viewport owning viewport (one owner per graph instance)
     * @param queue optional opaque queue, borrowed during this call
     * @return true on success, false after context.abort()
     */
    public boolean execute(RenderManager manager, ViewPort viewport, RenderQueue queue) {
        if (closed || executing) {
            throw new IllegalStateException("Graph closed or reentrant");
        }
        if (expanded) {
            throw new IllegalStateException("Call beginFrame before re-executing an expanded graph");
        }
        Objects.requireNonNull(manager);
        Objects.requireNonNull(viewport);
        compile();
        if (ownerViewport != null && (ownerViewport != viewport || ownerManager != manager)) {
            throw new IllegalStateException("Graph already owned by another viewport");
        }
        ownerViewport = viewport;
        ownerManager = manager;
        int w = viewport.getCamera().getWidth(), h = viewport.getCamera().getHeight();
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("Viewport must have positive dimensions");
        }
        releaseAll();
        if (width != w || height != h) {
            clearResources();
            width = w;
            height = h;
        }
        executing = true;
        executionCount++;
        executedPasses.clear();
        context.manager = manager;
        context.viewport = viewport;
        context.queue = queue;
        context.aborted = false;
        boolean success = false;
        try {
            for (int i = 0; i < schedule.size(); i++) {
                context.pass = schedule.get(i);
                for (Resource r : resources) {
                    if (r.first == i || !r.resolved && (r.imported != null || r.persistent != null)
                        && (context.pass.reads.contains(r.handle.index)
                            || context.pass.writes.contains(r.handle.index))) {
                        acquire(r);
                    }
                }
                for (int id : context.pass.reads) {
                    Persistent persistent = resources.get(id).persistent;
                    if (persistent != null && !persistent.valid) {
                        throw new IllegalStateException("Uninitialized persistent read: "
                            + resources.get(id).handle);
                    }
                }
                executedPasses.add(context.pass.name);
                manager.getRenderer().pushDebugGroup(viewport.getName() + "/" + context.pass.name);
                try {
                    synchronizeAccesses(context.pass);
                    context.pass.callback.execute(context);
                } finally {
                    manager.getRenderer().popDebugGroup();
                }
                if (context.aborted) {
                    return false;
                }
                for (int id : context.pass.writes) {
                    Persistent persistent = resources.get(id).persistent;
                    if (persistent != null) {
                        persistent.valid = true;
                    }
                }
                for (Resource r : resources) {
                    if (r.last == i && !isStorageExported(r)) {
                        release(r);
                    }
                }
            }
            for (Resource r : resources) {
                if (r.exported && (r.imported != null || r.persistent != null) && !r.resolved) {
                    if (r.persistent != null && !r.persistent.valid) {
                        throw new IllegalStateException("Uninitialized persistent export: " + r.handle);
                    }
                    acquire(r);
                }
            }
            outputValid = true;
            success = true;
            return true;
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                "FrameGraph pass failed: " + (context.pass == null ? "exports" : context.pass.name),
                error);
        } finally {
            try {
                publishShaderWrites(manager);
            } catch (RuntimeException barrierFailure) {
                success = false;
                throw barrierFailure;
            } finally {
                if (!success) {
                    releaseAll();
                    for (Persistent persistent : persistentResources.values()) {
                        persistent.valid = false;
                    }
                }
                context.pass = null;
                context.manager = null;
                context.viewport = null;
                context.queue = null;
                executing = false;
            }
        }
    }
    /**
     * Reads a retained final output after successful execution. Its object must not
     * be cached beyond the next execute, resize, clearResources or close.
     * @param handle previously exported resource
     * @param <T> resource type
     * @return retained output (possibly null for an import)
     */
    public <T> T getExported(ResourceHandle<T> handle) {
        check(handle);
        if (!outputValid || !resources.get(handle.index).exported) {
            throw new IllegalStateException("Output is not available");
        }
        return handle.type.cast(value(handle.index));
    }
    /**
     * Releases the pool, persistent allocations and exports, retaining declarations
     * and compiled topology for reuse. Persistent contents become invalid. Invoke
     * on the render thread with a current graphics context, never inside a pass.
     * Imported resources are not destroyed. Used by pipeline reshape/cleanup.
     */
    @SuppressWarnings("unchecked")
    public void clearResources() {
        if (executing) {
            throw new IllegalStateException("Cannot release during execution");
        }
        releaseAll();
        RuntimeException failure = null;
        for (Slot s : pool) {
            try {
                s.definition.destroy(s.value);
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        pool.clear();
        for (Persistent persistent : persistentResources.values()) {
            try {
                if (persistent.value != null) {
                    persistent.definition.destroy(persistent.value);
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                persistent.value = null;
                persistent.valid = false;
            }
        }
        width = -1;
        height = -1;
        if (failure != null) {
            throw failure;
        }
    }
    /** Permanently closes this graph; idempotent. Call on the render thread. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (executing) {
            throw new IllegalStateException("Cannot close executing graph");
        }
        try {
            clearResources();
        } finally {
            closed = true;
            ownerManager = null;
            ownerViewport = null;
        }
    }
}
