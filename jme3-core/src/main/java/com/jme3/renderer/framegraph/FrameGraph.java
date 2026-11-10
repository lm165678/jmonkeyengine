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

/**
 * Render-thread resource dependency graph with stable topological scheduling,
 * dead-pass elimination, lazy allocation and lifetime-based object pooling.
 * Build once, compile once, execute repeatedly. Configuration is frozen by compile;
 * call beginFrame() to rebuild topology while retaining allocations. Dynamic imported suppliers
 * are evaluated once per execution. See package documentation for a full example.
 *
 * <p>Each logical resource has at most one writer. Use separate logical resources
 * for successive versions/ping-pong operations; do not declare a read/write
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
        int writer = -1, first = -1, last = -1;
        boolean exported;
        Object value;
        boolean resolved;
        Slot slot;
        Persistent persistent;
    }
    private static final class Persistent {
        ResourceDefinition definition;
        Object value;
        boolean valid;
    }
    private static final class Slot {
        ResourceDefinition definition;
        Object value;
        boolean used;
    }
    /** Fluent access declaration returned by addPass; configure before compile. */
    public static final class PassBuilder {
        final FrameGraph graph;
        final String name;
        final int index;
        final FrameGraphPass callback;
        final Set<Integer> reads = new LinkedHashSet<>(), writes = new LinkedHashSet<>();
        final Set<Integer> dependencies = new LinkedHashSet<>();
        boolean sideEffect;
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
            return this;
        }
        /**
         * Declares the only writer of a logical output. Writing an imported object
         * is externally visible and keeps this pass alive automatically.
         * @param handle output resource
         * @return this declaration
         */
        public PassBuilder write(ResourceHandle<?> handle) {
            checkActive();
            graph.check(handle);
            writes.add(handle.index);
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
    private final Map<String, Persistent> persistentResources = new LinkedHashMap<>();
    private final FrameGraphContext context = new FrameGraphContext(this);
    private List<PassBuilder> schedule;
    private boolean executing, closed, outputValid;
    private int width = -1, height = -1;
    private RenderManager ownerManager;
    private ViewPort ownerViewport;

    private void mutable() {
        if (closed || executing || schedule != null) {
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
        passes.clear();
        schedule = null;
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
        for (Resource r : resources) {
            if (r.handle.getName().equals(name)) {
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
        Persistent persistent = persistentResources.get(name);
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
            persistentResources.put(name, persistent);
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
        Persistent persistent = persistentResources.get(name);
        return persistent != null && persistent.valid;
    }
    private <T> Resource add(String name, Class<T> type) {
        mutable();
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        for (Resource r : resources) {
            if (r.handle.getName().equals(name)) {
                throw new IllegalArgumentException("Duplicate resource: " + name);
            }
        }
        Resource r = new Resource();
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
        if (persistentResources.containsKey(name)) {
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
        if (persistentResources.containsKey(name)) {
            throw new IllegalArgumentException("Name reserved for persistent resource: " + name);
        }
        Resource r = add(name, type);
        r.imported = supplier;
        return (ResourceHandle<T>) r.handle;
    }
    /**
     * Keeps a final resource and its producer alive until the next execute or
     * clearResources. Read it after successful execution with getExported.
     * @param handle output to retain
     */
    public void exportResource(ResourceHandle<?> handle) {
        mutable();
        check(handle);
        resources.get(handle.index).exported = true;
    }
    /**
     * Adds a pass; declaration order only breaks ties between independent passes.
     * @param name unique debug name
     * @param callback body invoked on the render thread
     * @return access declaration; chain read/write/after/sideEffect before compile
     */
    public PassBuilder addPass(String name, FrameGraphPass callback) {
        mutable();
        Objects.requireNonNull(name);
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
     * Validates writers, uninitialized reads and cycles, then freezes the graph.
     * Unused passes are culled after validation. No GPU resources are allocated.
     * @throws IllegalStateException on invalid dependency declarations
     */
    public void compile() {
        if (closed || executing) {
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
        List<Integer> sorted = new ArrayList<>();
        Set<Integer> done = new HashSet<>();
        while (sorted.size() < passes.size()) {
            int next = -1;
            for (int i = 0; i < passes.size(); i++) {
                if (!done.contains(i) && done.containsAll(deps.get(i))) {
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
        }
        outputValid = false;
    }
    @SuppressWarnings("unchecked")
    private void acquire(Resource r) {
        if (r.resolved) {
            return;
        }
        if (r.imported != null) {
            r.value = r.handle.type.cast(r.imported.get());
            r.resolved = true;
            return;
        }
        if (r.persistent != null) {
            Persistent persistent = r.persistent;
            if (persistent.value == null) {
                persistent.value = Objects.requireNonNull(
                    persistent.definition.create(context.manager.getRenderer(), width, height));
            }
            r.value = r.handle.type.cast(persistent.value);
            r.resolved = true;
            return;
        }
        Slot slot = null;
        for (Slot candidate : pool) {
            if (!candidate.used && candidate.definition.getType() == r.handle.type
                && candidate.definition.equals(r.definition)) {
                slot = candidate;
                break;
            }
        }
        if (slot == null) {
            slot = new Slot();
            slot.definition = r.definition;
            slot.value = Objects.requireNonNull(
                r.definition.create(context.manager.getRenderer(), width, height),
                "Null allocation");
            pool.add(slot);
        }
        r.value = r.handle.type.cast(slot.value);
        r.resolved = true;
        r.slot = slot;
        slot.used = true;
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
        context.manager = manager;
        context.viewport = viewport;
        context.queue = queue;
        context.aborted = false;
        boolean success = false;
        try {
            for (int i = 0; i < schedule.size(); i++) {
                context.pass = schedule.get(i);
                for (Resource r : resources) {
                    if (r.first == i) {
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
                context.pass.callback.execute(context);
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
                    if (r.last == i && !r.exported) {
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
