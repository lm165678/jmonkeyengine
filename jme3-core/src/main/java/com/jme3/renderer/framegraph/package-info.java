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
 * A small single-queue FrameGraph for jME3. Application integration remains through
 * RenderManager, ViewPort and native materials; no separate scene graph is required.
 *
 * <h2>Complete opaque graph example</h2>
 * Build from simpleInitApp or another render-thread callback. Imports below come
 * from com.jme3.renderer.framegraph, com.jme3.texture and com.jme3.renderer.queue.
 * <pre>{@code
 * FrameGraph graph = new FrameGraph();
 * ResourceHandle<RenderTarget> hdr = graph.createResource("HDR",
 *     new RenderTargetDefinition(1f, 1, Image.Format.Depth24, Image.Format.RGBA16F));
 * ResourceHandle<FrameBuffer> output = graph.importResource("Viewport",
 *     FrameBuffer.class, viewPort::getOutputFrameBuffer);
 *
 * graph.addPass("Opaque", context -> {
 *     RenderTarget target = context.get(hdr);
 *     renderer.setFrameBuffer(target.getFrameBuffer());
 *     renderer.setBackgroundColor(viewPort.getBackgroundColor());
 *     renderer.clearBuffers(true, true, true);
 *     renderManager.setCamera(cam, false);
 *     context.getRenderQueue().renderQueue(RenderQueue.Bucket.Opaque, renderManager, cam);
 * }).write(hdr);
 * graph.addPass("Resolve", context -> {
 *     renderer.copyFrameBuffer(context.get(hdr).getFrameBuffer(), context.get(output), true);
 * }).read(hdr).write(output);
 *
 * renderManager.setPipeline(viewPort, new FrameGraphPipeline(graph));
 * System.out.println(graph.getExecutionOrder());
 * // Later, restore manager/viewport RenderPath selection:
 * renderManager.setPipeline(viewPort, null);
 * }</pre>
 * See jme3test.renderpath.TestFrameGraph for a runnable version with Forward/custom
 * graph/GPU-deferred switching and an automated offscreen pixel check.
 *
 * <h2>Dynamic frames and persistent resources</h2>
 * Static graphs are appropriate when topology is unchanged. For per-frame
 * topology, install a FrameGraphPipeline with a Consumer callback. The pipeline
 * calls beginFrame(width, height), invokes the callback, then compiles/executes.
 * Rebuild logical declarations every frame, not GPU allocations or the pipeline.
 * See jme3test.renderpath.TestDynamicFrameGraph for the complete commented example.
 * <pre>{@code
 * // 第一步：只安装一次管线，回调在每帧执行。
 * FrameGraphPipeline pipeline = new FrameGraphPipeline(graph -> {
 *     // 第二步：获取本帧句柄；底层持久资源在帧之间保留。
 *     ResourceHandle<RenderTarget> history =
 *             graph.findOrCreatePersistentResource("History", definition);
 *     ResourceHandle<FrameBuffer> output = graph.importResource(
 *             "Output", FrameBuffer.class, viewPort::getOutputFrameBuffer);
 *     // 第三步：首次或尺寸变化后初始化；实际项目可在这里绘制场景。
 *     if (!graph.isPersistentResourceValid("History")) {
 *         graph.addPass("Initialize", context -> {
 *             renderer.setFrameBuffer(context.get(history).getFrameBuffer());
 *             renderer.setBackgroundColor(viewPort.getBackgroundColor());
 *             renderer.clearBuffers(true, true, true);
 *         }).write(history);
 *     }
 *     // 第四步：读取持久画面，真正复制到视口输出（null 是默认帧缓冲）。
 *     graph.addPass("Present", context -> {
 *         renderer.copyFrameBuffer(context.get(history).getFrameBuffer(),
 *                 context.get(output), true);
 *     }).read(history).write(output);
 * });
 * renderManager.setPipeline(viewPort, pipeline);
 * }</pre>
 * findOrCreate(name, definition) retrieves a current-frame transient declaration;
 * names identify logical resources, while definition equality governs physical
 * pooling. Equal definitions do not merge simultaneously live resources. Different
 * definitions under the same name are rejected, not silently substituted.
 * Persistent resources are owned, isolated allocations. Writes keep their passes
 * alive; successful contents survive beginFrame until resize/clear/close or a failed
 * execution. Imports remain externally owned. beginFrame invalidates every old
 * handle and PassBuilder, including when a new resource has the same name.
 *
 * <h2>Resource and scheduling rules</h2>
 * Declare reads and writes before compilation. A resource has one producer;
 * separate logical handles represent subsequent versions (ping-pong targets).
 * Producer order follows resource dependencies, not declaration order. after()
 * adds an explicit dependency for non-resource effects. Cycles, missing producers,
 * multiple writers, foreign handles and transient read/write feedback are errors.
 * Write declarations do not clear textures; the writer initializes all used data.
 *
 * <p>Imported resources are caller-owned and may be null for the default framebuffer.
 * An import supplier resolves once per execution, so resized viewport outputs and
 * external history textures can change without recompiling. A write to an import
 * keeps its pass alive. Use sideEffect() for visible work without an output handle,
 * such as a direct queue draw, or exportResource(handle) for a final offscreen image.
 * Read an export with getExported only after successful execution. It expires on
 * the next execution/resize/clear/close. Do not keep transient object references.</p>
 *
 * <p>Unused passes allocate nothing. Live resources allocate at first use and return
 * to a viewport-local pool at last use. Equal immutable ResourceDefinitions can
 * reuse the same physical object only when lifetimes do not overlap. This is
 * object reuse, not driver memory aliasing. Exported outputs remain pinned until
 * the next execution. Contents of reused objects are undefined. On resize the
 * pool is disposed and reconstructed lazily; imports are never destroyed.</p>
 *
 * <h2>Execution, cleanup and extension</h2>
 * compile() freezes topology until beginFrame(); execute() reuses the schedule.
 * All callbacks, allocation and release
 * happen on the render thread with a current graphics context. A pass exception
 * releases live references, stops downstream work, and reports its name. abort()
 * returns false without publishing exports; it does not undo submitted draws.
 * Use abort before consuming geometry when falling back to the original renderer.
 *
 * <p>Implement ResourceDefinition for buffer objects or application-specific bundles;
 * implement equals/hashCode only when physical reuse is safe. destroy normally
 * calls NativeObject.dispose rather than deleting OpenGL handles directly. A custom
 * pass can use normal materials, compute shaders or renderer calls. Declare GPU
 * resource usage and use the renderer's synchronization APIs: graph ordering alone
 * does not synthesize barriers for arbitrary raw GL calls. jME dispatchCompute
 * already supplies the SSBO barrier used by the tiled pipeline.</p>
 *
 * <p>FrameGraphPipeline occupies the existing opaque extension point. Transparent,
 * sky, GUI and SceneProcessors retain their established scheduling. To host a graph
 * inside a post-processing callback, execute it explicitly there and call close()
 * during that processor's cleanup. Import external scene color/depth/history;
 * do not assume a transient allocation persists across executions. The framework
 * does not migrate existing filters automatically or introduce asynchronous GL
 * contexts. DeferredPipeline now schedules LightCulling, GBuffer and DeferredLighting
 * through a graph, while retaining persistent program/light-data caches.</p>
 */
package com.jme3.renderer.framegraph;
