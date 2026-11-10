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
/**
 * Optional desktop deferred rendering with shared material surface shaders.
 *
 * <h2>Integration</h2>
 * <pre>
 * renderManager.setRenderPath(RenderPath.TiledDeferredCPU);
 * renderManager.setRenderPath(RenderPath.TiledDeferredGPU);
 * renderManager.setRenderPath(RenderPath.Forward);
 * // Optional per-viewport override; Inherit follows the manager.
 * viewPort.setRenderPath(RenderPath.Deferred);
 * renderManager.setDeferredTileSize(16);
 * </pre>
 * Forward remains the default. CPU requires OpenGL 3.3 with five floating-point
 * MRT attachments; GPU requires OpenGL 4.3/SSBOs. Auto prefers GPU. Unsupported
 * GPU contexts/capacities select CPU. Unsupported viewport states select Forward,
 * with a diagnostic available from the processor. MSAA targets use Forward;
 * use a single-sample target with FXAA for deferred rendering.
 *
 * Each viewport owns its resources. Removing a viewport releases its managed
 * pipeline. Forward transitions release the managed pipeline; custom model
 * registrations on RenderManager survive recreation. All configuration and
 * cleanup operations must run on the render thread. Explicitly installed
 * TiledDeferredProcessor instances are also supported and take precedence over
 * the managed path. The manager schedules their opaque pass after ordinary
 * postQueue callbacks, so shadows, glow and SSAO can inspect the full queue.
 *
 * <h2>Pipeline and pass ownership</h2>
 * RenderManager delegates lifecycle management to ViewportPipelines. The public
 * RenderPipeline contract replaces the opaque stage, after auxiliary postQueue
 * callbacks and before the engine flushes its remaining queues. Each installed
 * instance belongs to exactly one viewport. setPipeline(vp, null) restores the
 * normal RenderPath selection; installing ForwardPipeline explicitly retains
 * the original opaque queue renderer even if the global path is deferred.
 * <pre>
 * DeferredPipeline pipeline = new DeferredPipeline(assetManager,
 *         renderManager.getDeferredShadingModels());
 * pipeline.setLightCullingMode(LightCullingMode.GPU);
 * pipeline.setTileSize(16);
 * renderManager.setPipeline(viewPort, pipeline);
 * // Backend policy changes do not replace the pipeline or its render targets.
 * pipeline.setLightCullingMode(LightCullingMode.CPU);
 * // Give path selection back to the manager:
 * renderManager.setPipeline(viewPort, null);
 * </pre>
 * TiledDeferredProcessor is a compatibility facade over DeferredPipeline, not
 * a second renderer. The latter orchestrates these independent internal passes:
 * <ol>
 * <li>LightCullingPass uploads lights and selects CpuLightCullingBackend or
 * GpuLightCullingBackend; None skips tile construction.</li>
 * <li>GBufferPass evaluates native material surface techniques.</li>
 * <li>DeferredLightingPass composes color and depth into the viewport target.</li>
 * </ol>
 * This ordering preserves vertex-lighting inputs; it does not introduce a new
 * depth-culling algorithm. DeferredResources is the sole native resource owner.
 * Passes borrow its textures, programs and buffers through DeferredRenderContext.
 * Resize releases size-dependent targets. Cleanup releases all native resources,
 * clears borrowed references and permits pipeline reinitialization. Scene objects
 * are not retained in the batch pool after the frame. ShadingModelRegistry versions
 * invalidate lighting programs only; culling programs survive model updates.
 * These explicit dependencies provide a future FrameGraph integration point;
 * no FrameGraph or automatic resource aliasing is introduced here.
 *
 * <h2>Material contract</h2>
 * A definition opts in with {@code ShadingModelId N} (or {@code ShadingId N})
 * and a named {@code Technique GBuffer}. ShadingModel exposes built-in IDs:
 * zero is ForwardOnly, 1 Lighting, 2 PBRLighting, 3 Unshaded. Custom IDs 4..65535
 * require {@code RenderManager.registerDeferredShadingModel(id, glsl)}, or
 * registration directly on an explicitly installed processor. Unknown models
 * keep their Forward technique. Material definition export preserves the ID.
 *
 * GBuffer techniques use DeferredSurface.glsllib to write linear MRT records:
 * <ul>
 * <li>0, RGBA32F: diffuse RGB and exact integer model ID (zero is background).</li>
 * <li>1, RGBA16F: world-space normal and shininess/roughness.</li>
 * <li>2, RGBA16F: direct-light specular RGB/F0.</li>
 * <li>3, RGBA16F: precomputed indirect/emissive RGB and surface alpha.</li>
 * <li>4, RGBA16F: legacy reflection coefficients/flags or terrain modifiers.</li>
 * <li>Depth24: ordinary depth, also written to the composed scene target.</li>
 * </ul>
 * Declare {@code Int DeferredShadingId} without a default. A temporary forced
 * parameter supplies the definition ID; material instances are not mutated.
 * Custom functions use the first four records; their fourth RGB record is an
 * ambient response multiplied by the batch's ambient light. The built-in models
 * instead store already-evaluated indirect light in that record. See the ID 7
 * CustomLambert example for a complete Forward/GBuffer pair.
 *
 * <h2>Material features and composition</h2>
 * Lighting, PBRLighting and Unshaded reuse their native surface and vertex
 * shaders, including normal/parallax mapping, lightmaps, alpha discard, vertex
 * colors, skinning, morph targets and instancing. PBR retains metallic/roughness,
 * specular/glossiness, emissive maps and up to three blended probes. Ambient,
 * probes and emission are evaluated once while writing the surface. Phong
 * retains reflection, color ramps and material fog. VertexLighting deliberately
 * evaluates direct lighting at vertices and stores the interpolated result as
 * unlit surface data, preserving its original interpolation semantics.
 * Terrain, TerrainLighting, PBRTerrain and AdvancedPBRTerrain have adapters.
 *
 * Independent scene light scopes and color-ramp textures form separate batches;
 * they are not unioned across unrelated geometry. Batch results compose with
 * depth testing. Transparent/translucent, sky and GUI queues use Forward, as do
 * custom render states without a compatible surface contract. Existing shadow
 * maps, PostShadow geometry passes and shadow filters are retained. This is
 * legacy shadow compositing, not per-light shadow sampling inside the tiled
 * lighting shader. Other post filters consume the composed scene color/depth.
 *
 * <h2>Culling and limits</h2>
 * CPU builds dynamically sized conservative screen-space tile lists. GPU uses
 * compute workgroups and SSBOs without CPU readback, with a storage barrier
 * before lighting. GPU tile capacity grows with the light count within a 64 MiB
 * budget and the driver's SSBO block limit. If necessary, capacity is reduced;
 * overflowing tiles shade all lights so overflow never drops illumination.
 * Ordinary Deferred loops all lights without building tile lists. GPU culling
 * intersects light spheres with tile frusta, including near-plane intersections.
 * Legacy ColorRamp and environment-reflection batches retain screen-box bounds
 * because those lighting terms may contribute at zero attenuation. There is no
 * tile min/max-depth reduction in this implementation.
 * Dense lights and many independent scopes can reduce its performance benefit.
 * CPU and GPU use the same direct-light shader. GPU atomic ordering and half
 * precision surfaces can produce small rounding differences. Compare performance
 * on representative content rather than assuming deferred always wins.
 *
 * <h2>Regression entry points</h2>
 * Gradle tasks tiledDeferredSmoke, tiledDeferredSmokeGL33 and
 * tiledDeferredSmokeOverflow check GPU/CPU parity, Forward round trips, odd-size
 * resize, fallback and overflow. renderPathSmoke runs each ported reference
 * scene; renderPathSuite runs all scenes, including material-feature and
 * viewport-isolation regressions. PNG captures are written under the examples'
 * build/renderpath-tests directory. Probe baking completes before pixel checks.
 * renderPipelineSuite additionally exercises direct Pipeline installation and
 * replacement with ForwardPipeline, independently of the compatibility adapter.
 */
package com.jme3.renderer.deferred;
