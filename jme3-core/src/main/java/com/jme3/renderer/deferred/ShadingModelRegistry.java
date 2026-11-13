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
import com.jme3.material.MaterialDef;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Render-thread registry; version changes invalidate only lighting programs.
 * Share {@code renderManager.getDeferredShadingModels()} with directly installed
 * pipelines when models should survive pipeline replacement.
 * <p>Ordinary application usage does not load GLSL or register numeric IDs:</p>
 * <pre>{@code
 * Material material = new Material(assetManager, "Materials/Tiled/CustomLambert.j3md");
 * geometry.setMaterial(material);
 * renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
 * }</pre>
 * CustomLambert.j3md in jme3-examples declares
 * {@code DeferredLightingShader Materials/Tiled/CustomLambert.frag} and provides
 * both default Forward and GBuffer techniques. Its GLSL source defines
 * {@code vec4 deferredSurfaceShading()}; the engine resolves
 * and caches its registry-local ID. See MaterialDef.setDeferredLightingShader
 * for the declaration contract. The numeric register API remains available for
 * low-level integrations that already generate their own lighting source.

 */
public final class ShadingModelRegistry {
    private final Map<Integer, String> sources = new TreeMap<>();
    private final Map<Integer, String> view = Collections.unmodifiableMap(sources);
    private long version;
    private int nextAutomaticId = 65535;
    private final Map<MaterialDef, ResolvedModel> resolved = new IdentityHashMap<>();
    private final Map<AssetManager, Map<String, Integer>> automatic = new IdentityHashMap<>();
    private final Map<Integer, String> materialSources = new HashMap<>();
    private static final class ResolvedModel {
        String path;
        int requested, id;
        AssetManager assets;
    }

    /**
     * Resolves a MaterialDef's model. Normally called by the engine; application
     * code only creates new Material(assetManager, "Materials/MyMaterial.j3md").
     * Built-in/manual definitions return their declared ID. A definition with
     * DeferredLightingShader is loaded once and registered automatically; source
     * must define deferredSurfaceShading(), or the legacy per-light deferredLighting entry.
     * Automatic IDs use the high end of the custom range to avoid common explicit
     * IDs. Conflicting explicit material IDs fail instead of silently replacing
     * another model. Invalid assets/GLSL declarations fail with the asset path.
     * @param definition material definition
     * @param assets asset manager used when the definition has no owning manager
     * @return registry-local model ID, suitable for DeferredShadingId
     */
    public int resolve(MaterialDef definition, AssetManager assets) {
        String path = definition.getDeferredLightingShader();
        int requested = definition.getShadingId();
        if (path == null) {
            return requested;
        }
        if (requested > 0 && requested < 4) {
            throw new IllegalArgumentException(
                "Custom lighting cannot use built-in ID: " + requested);
        }
        AssetManager loader =
            definition.getAssetManager() == null ? assets : definition.getAssetManager();
        Objects.requireNonNull(loader, "assetManager");
        ResolvedModel cached = resolved.get(definition);
        if (cached != null && cached.requested == requested && cached.assets == loader
            && cached.path.equals(path)) {
            return cached.id;
        }
        Map<String, Integer> paths = automatic.computeIfAbsent(loader, key -> new HashMap<>());
        Integer existing = requested == 0 ? paths.get(path) : null;
        int id;
        if (existing != null) {
            id = existing;
        } else {
            String source = (String) loader.loadAsset(path);
            boolean fullSurface =
                java.util.regex.Pattern.compile("\\bvec4\\s+deferredSurfaceShading\\s*\\(")
                    .matcher(source)
                    .find();
            boolean legacyPerLight =
                java.util.regex.Pattern.compile("\\bvec3\\s+deferredLighting\\s*\\(")
                    .matcher(source)
                    .find();
            if (!fullSurface && !legacyPerLight) {
                throw new IllegalArgumentException("Expected vec4 deferredSurfaceShading() in "
                    + path + " (legacy vec3 deferredLighting(...) is also accepted)");
            }
            if (requested == 0) {
                while (nextAutomaticId >= 4 && sources.containsKey(nextAutomaticId)) {
                    nextAutomaticId--;
                }
                if (nextAutomaticId < 4) {
                    throw new IllegalStateException("No automatic shading IDs remain");
                }
                id = nextAutomaticId--;
            } else
                id = requested;
            if (fullSurface) {
                source =
                    source.replaceAll("\\bdeferredSurfaceShading\\b", "deferredSurfaceModel" + id);
            } else {
                source = source.replaceAll("\\bdeferredLighting\\b", "deferredModel" + id);
            }
            if (sources.containsKey(id) && !sources.get(id).equals(source)) {
                throw new IllegalArgumentException("Conflicting shading ID " + id + " for " + path);
            }
            register(id, source);
            materialSources.put(id, source);
            if (requested == 0) {
                paths.put(path, id);
            }
        }
        cached = new ResolvedModel();
        cached.path = path;
        cached.requested = requested;
        cached.assets = loader;
        cached.id = id;
        resolved.put(definition, cached);
        return id;
    }

    /**
     * Low-level registration for generated GLSL; applications normally declare
     * DeferredLightingShader in their MaterialDef instead. Full-surface source
     * for explicit ID 7 defines the following entry:
     * <pre>{@code
     * vec4 deferredSurfaceModel7() {
     *     // This example model defines record4 as final color/opacity.
     *     return getGBuffer4();
     * }
     * }</pre>
     * Each model defines its own decoder. Shared shader helpers expose raw
     * getGBuffer0..4(), getShadingId(), getSurfaceDepth(), getWorldPosition(),
     * getViewDirection(position), getLightList() and getLight(list, entry, position).
     * The returned DeferredLight contains color, direction and attenuation.
     * Full-surface functions return final RGB/opacity and apply attenuation themselves.
     * Only gbuffer0.a is reserved for shadingId; depth has its own attachment.
     *
     * <p>Compatibility: vec3 deferredModel7(vec4 record0, vec4 record1, vec4 record2,
     * vec4 record3, vec3 viewDir, vec3 lightDir, vec3 lightColor) remains supported.
     * That old per-light API uses record3.rgb as ambient response and record3.a as
     * opacity; its adapter applies attenuation. Those conventions apply only to
     * the legacy adapter, not other models. Compilation is deferred until the
     * combined lighting shader is needed; register does not validate GLSL syntax.</p>
     * @param id custom ID from 4 through 65535; 0..3 are reserved
     * @param source GLSL defining deferredSurfaceModelN (or legacy deferredModelN)
     * @throws IllegalArgumentException if the ID is outside the custom range
     */
    public void register(int id, String source) {
        if (id < 4 || id > 65535) {
            throw new IllegalArgumentException("Custom model ID must be 4..65535");
        }
        Objects.requireNonNull(source, "source");
        if (materialSources.containsKey(id) && !materialSources.get(id).equals(source)) {
            throw new IllegalArgumentException("ID is owned by a MaterialDef: " + id);
        }
        if (!source.equals(sources.put(id, source))) {
            version++;
        }
    }
    /** @return true for built-in deferred IDs 1..3 or registered custom IDs */
    public boolean supports(int id) {
        return id >= 1 && id <= 3 || sources.containsKey(id);
    }
    /** @return revision incremented only when registered source actually changes */
    public long getVersion() {
        return version;
    }
    /** @return unmodifiable live view of custom sources; built-in models are omitted */
    public Map<Integer, String> getSources() {
        return view;
    }
}
