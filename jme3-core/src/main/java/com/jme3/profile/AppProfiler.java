/*
 * Copyright (c) 2014-2021 jMonkeyEngine
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

package com.jme3.profile;

import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue.Bucket;


/**
 *  Can be hooked into the application (and render manager)
 *  to receive callbacks about specific frame steps.  It is up
 *  to the specific implementation to decide what to do with
 *  the information.
 *
 *  @author    Paul Speed
 */
public interface AppProfiler {

    /**
     * Begins a nested render-thread scope. Existing profiler implementations may
     * ignore scopes. Calls must be balanced in finally, including failed passes.
     * DetailedProfiler includes scopes in its CPU/GPU timing tree.
     * <pre>{@code
     * // 第一步：在渲染线程获取 RenderManager 的 profiler。
     * AppProfiler profiler = renderManager.getProfiler();
     * // 第二步：成对标记工作；FrameGraph 自动标记其所有已执行 Pass。
     * if (profiler != null) {
     *     profiler.beginScope("Custom work");
     * }
     * try {
     *     renderCustomWork();
     * } finally {
     *     if (profiler != null) {
     *         profiler.endScope();
     *     }
     * }
     * }</pre>
     * @param name scope name; slashes create display groups
     */
    default void beginScope(String name) {
    }

    /** Ends the most recent scope on the same thread. */
    default void endScope() {
    }

    /**
     *  Called at the beginning of the specified AppStep.
     *
     * @param step the application-level step that's about to begin
     */
    public void appStep(AppStep step);

    /**
     * Called as a substep of the previous AppStep
     *
     * @param additionalInfo information about the substep
     */
    public void appSubStep(String... additionalInfo);

    /**
     *  Called at the beginning of the specified VpStep during
     *  the rendering of the specified ViewPort.  For bucket-specific
     *  steps the Bucket parameter will be non-null.
     *
     * @param step the ViewPort-level step that's about to begin
     * @param vp which ViewPort is being processed
     * @param bucket which Bucket is being processed
     */
    public void vpStep(VpStep step, ViewPort vp, Bucket bucket);

    /**
     * Called at the beginning of the specified SpStep (SceneProcessor step).
     * For more detailed steps it is possible to provide additional information as strings, like the name of the processor.
     *
     * @param step the SceneProcessor step that's about to begin
     * @param additionalInfo information about the SceneProcessor step
     */
    public void spStep(SpStep step, String... additionalInfo);
}


