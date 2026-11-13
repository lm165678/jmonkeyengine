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
 * OpaquePipeline contract replaces the opaque stage, after auxiliary postQueue
 * callbacks and before the engine flushes its remaining queues. Each installed
 * instance belongs to exactly one viewport. setPipeline(vp, null) restores the
 * normal RenderPath selection; installing OpaqueForwardPipeline explicitly retains
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
 * depth-culling algorithm. FrameGraph owns the transient GBuffer allocation pool.
 * DeferredResources owns persistent light textures, buffers and shader programs.
 * Passes borrow its textures, programs and buffers through DeferredRenderContext.
 * Resize releases size-dependent targets. Cleanup releases all native resources,
 * clears borrowed references and permits pipeline reinitialization. Scene objects
 * are not retained in the batch pool after the frame. ShadingModelRegistry versions
 * invalidate lighting programs only; culling programs survive model updates.
 * The three passes declare their dependencies in a compiled FrameGraph. GBuffer
 * resources return to the pool at last use and are reused between batches/frames.
 * See com.jme3.renderer.framegraph for custom graph construction and ownership.
 *
 * <h2>Material contract</h2>
 * A definition opts in with {@code ShadingModelId N} (or {@code ShadingId N})
 * and a named {@code Technique GBuffer}. ShadingModel exposes built-in IDs:
 * zero is ForwardOnly, 1 Lighting, 2 PBRLighting, 3 Unshaded. Custom IDs 4..65535
 * can be explicitly registered by low-level integrations. Normal applications
 * instead load Material from .j3md; declare the custom source in that asset:
 * <pre>{@code
 * MaterialDef Custom Deferred Lambert {
 *     DeferredLightingShader Materials/Tiled/CustomLambert.frag
 *     // MaterialParameters and default/GBuffer techniques follow.
 * }
 * }</pre>
 * The engine loads the declared source, whose entry point is deferredSurfaceShading,
 * and assigns a registry-local ID. Omit ShadingId for automatic assignment or
 * declare an explicit custom ID for checked association. The full runnable asset
 * is Materials/Tiled/CustomLambert.j3md in jme3-examples; no application-side
 * loadAsset(.frag) or registration is required. Both declaration and explicit ID
 * survive material definition export. Unknown manual models keep Forward.
 * Technique lighting execution is declared with LightModel MultiPass/SinglePass;
 * legacy LightMode is accepted as an alias. This execution mode is independent
 * of the deferred shading model selected by the material.
 *
 * GBuffer techniques use DeferredSurface.glsllib to write five raw records plus
 * depth. Attachment uniforms are gbuffer0..gbuffer4 and gbufferDepth. Only
 * gbuffer0.a (shadingId) and the depth attachment have shared meanings. Other
 * channels, including opacity, are defined by each model's writer/decoder pair.
 * Main obtains getShadingId() and dispatches through subfaceShading(shadingId).
 * Built-in decoders and their complete channel maps are documented separately in
 * Common/ShaderLib/Deferred/LightingModel.glsllib, PbrModel.glsllib and
 * UnshadedModel.glsllib. No shared decoder assumes all models have a normal,
 * specular color, ambient term or reflection record.
 *
 * <p>Custom material usage:</p>
 * <pre>{@code
 * // .j3md: DeferredLightingShader Materials/Tiled/CustomLambert.frag
 * // Its GBuffer technique writes its own layout with writeGBuffer(...).
 * // The lighting source defines a full-surface function, e.g.:
 * vec4 deferredSurfaceShading() {
 *     vec3 diffuse = getGBuffer0().rgb;
 *     vec3 normal = normalize(getGBuffer3().xyz);
 *     float opacity = getGBuffer4().a;
 *     vec3 position = getWorldPosition();
 *     DeferredLightList lights = getLightList();
 *     vec3 color = getGBuffer1().rgb * AmbientColor;
 *     for (int entry = 0; entry < lights.count; ++entry) {
 *         DeferredLight light = getLight(lights, entry, position);
 *         color += diffuse * light.color * max(dot(normal, light.direction), 0.0)
 *                 * light.attenuation;
 *     }
 *     return vec4(color, opacity);
 * }
 * }</pre>
 * The full example is CustomLambert.j3md, CustomLambertGBuffer.frag and
 * CustomLambert.frag in jme3-examples. It deliberately stores the normal in
 * record3 and opacity in record4, unlike built-in models. The Java application
 * only constructs Material; the registry associates the declared shader/ID.
 * The old per-light deferredLighting entry is retained via a compatibility
 * adapter with its original record3 ambient/opacity contract.
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
 * replacement with OpaqueForwardPipeline, independently of the compatibility adapter.
 */
package com.jme3.renderer.deferred;
