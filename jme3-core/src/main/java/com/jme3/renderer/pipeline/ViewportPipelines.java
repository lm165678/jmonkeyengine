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
package com.jme3.renderer.pipeline;

import com.jme3.material.MaterialDef;
import com.jme3.post.SceneProcessor;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.deferred.ShadingModelRegistry;
import com.jme3.renderer.deferred.TiledDeferredProcessor;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal RenderManager lifecycle coordinator. Pipelines and native allocations
 * are never shared between viewports. SceneProcessor-owned adapters are borrowed,
 * not owned here. Applications should use RenderManager's pipeline methods.
 */
public final class ViewportPipelines {
    private final RenderManager manager;
    private final Map<ViewPort, Entry> entries = new IdentityHashMap<>();
    private final ShadingModelRegistry models = new ShadingModelRegistry();

    private static final class Entry {
        final RenderPipeline pipeline;
        final boolean replacement;
        boolean initialized;
        int width, height;
        Entry(RenderPipeline pipeline, boolean replacement) {
            this.pipeline = pipeline;
            this.replacement = replacement;
        }
    }

    public ViewportPipelines(RenderManager manager) {
        this.manager = Objects.requireNonNull(manager);
    }

    public ShadingModelRegistry getShadingModels() {
        return models;
    }

    public RenderPipeline get(ViewPort viewport) {
        Entry entry = entries.get(viewport);
        return entry == null ? null : entry.pipeline;
    }

    public void set(ViewPort viewport, RenderPipeline pipeline) {
        Objects.requireNonNull(viewport, "viewport");
        if (!manager.getPreViews().contains(viewport) && !manager.getMainViews().contains(viewport)
            && !manager.getPostViews().contains(viewport)) {
            throw new IllegalArgumentException("Viewport does not belong to this RenderManager");
        }
        if (pipeline != null) {
            for (SceneProcessor processor : viewport.getProcessors()) {
                if (processor instanceof TiledDeferredProcessor) {
                    throw new IllegalStateException(
                        "Remove the explicit deferred processor before replacing the pipeline");
                }
            }
            Entry current = entries.get(viewport);
            if (current != null && current.pipeline == pipeline) {
                return;
            }
            for (Entry entry : entries.values()) {
                if (entry.pipeline == pipeline) {
                    throw new IllegalArgumentException(
                        "Pipeline already belongs to another viewport");
                }
            }
            if (pipeline.isInitialized()) {
                throw new IllegalArgumentException("Install an uninitialized pipeline");
            }
        }
        remove(viewport);
        if (pipeline != null) {
            entries.put(viewport, new Entry(pipeline, true));
        }
    }

    public void render(ViewPort viewport) {
        Entry entry = entries.get(viewport);
        if (entry != null && entry.replacement) {
            render(entry, viewport);
            return;
        }
        // Called after ordinary postQueue passes, regardless of processor order.
        for (SceneProcessor processor : viewport.getProcessors()) {
            if (processor instanceof TiledDeferredProcessor) {
                remove(viewport);
                processor.postQueue(viewport.getQueue());
                return;
            }
        }
        RenderPath path = viewport.getRenderPath() == RenderPath.Inherit ? manager.getRenderPath()
                                                                         : viewport.getRenderPath();
        if (path == RenderPath.Forward) {
            remove(viewport);
            return;
        }
        if (entry == null) {
            if (viewport.getQueue().getOpaqueQueue().size() == 0) {
                return;
            }
            MaterialDef definition =
                viewport.getQueue().getOpaqueQueue().get(0).getMaterial().getMaterialDef();
            entry =
                new Entry(new TiledDeferredProcessor(definition.getAssetManager(), models), false);
            entries.put(viewport, entry);
        }
        TiledDeferredProcessor adapter = (TiledDeferredProcessor) entry.pipeline;
        adapter.setMode(TiledDeferredProcessor.Mode.valueOf(path.name()));
        adapter.setTileSize(manager.getDeferredTileSize());
        render(entry, viewport);
    }

    private void render(Entry entry, ViewPort viewport) {
        int width = viewport.getCamera().getWidth(), height = viewport.getCamera().getHeight();
        if (!entry.initialized) {
            if (entry.pipeline.isInitialized()) {
                entries.remove(viewport);
                throw new IllegalStateException("Pipeline was initialized by a different owner");
            }
            try {
                entry.pipeline.initialize(manager, viewport);
                entry.initialized = true;
                entry.width = width;
                entry.height = height;
            } catch (RuntimeException | Error failure) {
                entries.remove(viewport);
                try {
                    entry.pipeline.cleanup();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } else if (entry.width != width || entry.height != height) {
            reshape(viewport, width, height);
        }
        entry.pipeline.render(viewport.getQueue());
    }

    public void reshape(ViewPort viewport, int width, int height) {
        Entry entry = entries.get(viewport);
        if (entry != null && entry.initialized
            && (entry.width != width || entry.height != height)) {
            entry.pipeline.reshape(width, height);
            entry.width = width;
            entry.height = height;
        }
    }

    public void remove(ViewPort viewport) {
        Entry entry = entries.remove(viewport);
        if (entry != null && entry.initialized) {
            entry.pipeline.cleanup();
        }
    }

    public void cleanup() {
        // Remove ownership before invoking callbacks, including failing ones.
        Entry[] owned = entries.values().toArray(new Entry[0]);
        entries.clear();
        Throwable failure = null;
        for (Entry entry : owned) {
            if (!entry.initialized) {
                continue;
            }
            try {
                entry.pipeline.cleanup();
            } catch (RuntimeException | Error error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure instanceof RuntimeException) {
            throw(RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw(Error) failure;
        }
    }
}
