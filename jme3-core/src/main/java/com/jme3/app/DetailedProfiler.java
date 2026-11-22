/*
 * Copyright (c) 2017-2021 jMonkeyEngine
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
package com.jme3.app;

import com.jme3.profile.*;
import com.jme3.renderer.Caps;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;

import java.util.*;

/**
 * Created by Nehon on 25/01/2017.
 * Nested AppProfiler scopes use exclusive CPU segments; the display aggregates
 * descendants into parent totals. Multiple segments of a path in one frame are
 * summed. GPU samples are collected only after every query for that path/frame
 * is available, including paths absent from later frames. No query wait is used.
 */
public class DetailedProfiler implements AppProfiler {

    private static final int MAX_FRAMES = 100;
    private Map<String, StatLine> data;
    private Map<String, StatLine> pool;
    private long startFrame;
    private static int currentFrame = 0;
    private String prevPath = null;
    private boolean frameEnded = false;
    private Renderer renderer;
    private boolean ongoingGpuProfiling = false;
    private final Deque<String> scopes = new ArrayDeque<>();
    private final Deque<String[]> scopeContexts = new ArrayDeque<>();
    private final Deque<VpStep> scopeVpSteps = new LinkedList<>();
    private long segmentStart;


    private String curAppPath = null;
    private String curVpPath = null;
    private String curSpPath = null;
    private VpStep lastVpStep = null;

    private final StringBuilder path = new StringBuilder(256);
    private final StringBuilder vpPath = new StringBuilder(256);

    private final Deque<Integer> idsPool = new ArrayDeque<>(100);

    StatLine frameTime;


    @Override
    public void appStep(AppStep step) {

        curAppPath = step.name();

        if (step == AppStep.BeginFrame) {
            startFrame = System.nanoTime();
            curVpPath = null;
            curSpPath = "";
            lastVpStep = null;
            if (data == null) {
                data = new LinkedHashMap<>();
                pool = new HashMap<>();
                frameTime = new StatLine(currentFrame);
            }
            if (frameTime.isActive()) {
                frameTime.setValueCpu(System.nanoTime() - frameTime.getValueCpu());
                frameTime.closeFrame();

            }
            frameTime.setNewFrameValueCpu(System.nanoTime());

            frameEnded = false;
            if (renderer != null) {
                for (StatLine statLine : pool.values()) {
                    for (Iterator<List<Integer>> batches = statLine.taskIds.iterator(); batches.hasNext();) {
                        List<Integer> batch = batches.next();
                        boolean ready = true;
                        for (int id : batch) {
                            ready &= renderer.isTaskResultAvailable(id);
                        }
                        if (!ready) {
                            break;
                        }
                        long total = 0;
                        for (int id : batch) {
                            total += renderer.getProfilingTime(id);
                            idsPool.push(id);
                        }
                        statLine.setValueGpu(total);
                        batches.remove();
                    }
                }
            }
            data.clear();
            scopes.clear();
            scopeContexts.clear();
            scopeVpSteps.clear();
        }

        if (data != null) {
            String path = getPath(step.name());
            if (step == AppStep.EndFrame) {
                if (frameEnded) {
                    return;
                }
                addStep(path, System.nanoTime());
                StatLine end = data.get(path);
                end.setValueCpu(System.nanoTime() - startFrame);
                frameEnded = true;
            } else {
                addStep(path, System.nanoTime());
            }
        }
        if (step == AppStep.EndFrame) {

            closeFrame();
        }
    }
    
    
    @Override
    public void appSubStep(String... additionalInfo) {
        if (data != null) {
            String pathStep = getPath("", additionalInfo);
            path.setLength(0);
            path.append(curAppPath).append(pathStep);
            addStep(path.toString(), System.nanoTime());
        }
    }

    private void closeFrame() {
        //close frame
        if (data != null) {
            if (ongoingGpuProfiling && renderer != null) {
                renderer.stopProfiling();
                ongoingGpuProfiling = false;
            }
            prevPath = null;

            for (StatLine statLine : data.values()) {
                statLine.closeFrame();
            }
            currentFrame++;
        }
    }

    @Override
    public void vpStep(VpStep step, ViewPort vp, RenderQueue.Bucket bucket) {

        if (data != null) {
            vpPath.setLength(0);
            vpPath.append(vp.getName()).append("/")
                    .append((bucket == null ? step.name() : bucket.name() + " Bucket"));
            path.setLength(0);
            if ((lastVpStep == VpStep.PostQueue || lastVpStep == VpStep.PostFrame) && bucket != null) {
                path.append(curAppPath).append("/").append(curVpPath).append(curSpPath).append("/")
                    .append(vpPath);
                curVpPath = vpPath.toString();
            } else {
                if (bucket != null) {
                    path.append(curAppPath).append("/").append(curVpPath).append("/")
                        .append(bucket.name() + " Bucket");
                } else {
                    path.append(curAppPath).append("/").append(vpPath);
                    curVpPath = vpPath.toString();
                }
            }
            lastVpStep = step;

            addStep(path.toString(), System.nanoTime());
        }
    }

    @Override
    public void spStep(SpStep step, String... additionalInfo) {

        if (data != null) {
            curSpPath = getPath("", additionalInfo);
            path.setLength(0);
            path.append(curAppPath).append("/").append(curVpPath).append(curSpPath);
            addStep(path.toString(), System.nanoTime());
        }

    }

    public Map<String, StatLine> getStats() {
        if (data != null) {
            return data; //new LinkedHashMap<>(data);
        }
        return null;
    }

    public double getAverageFrameTime() {
        return frameTime.getAverageCpu();
    }


    private void addStep(String path, long value) {
        if (!scopes.isEmpty() && !path.startsWith(scopes.peek())) {
            path = scopes.peek() + "/" + path;
        }
        if (ongoingGpuProfiling && renderer != null) {
            renderer.stopProfiling();
            ongoingGpuProfiling = false;
        }

        if (prevPath != null) {
            StatLine prevLine = data.get(prevPath);
            if (prevLine != null) {
                prevLine.setValueCpu(prevLine.getValueCpu() + value - segmentStart);
            }
        }

        StatLine line = pool.get(path);
        if (line == null) {
            line = new StatLine(currentFrame);
            pool.put(path, line);
        }
        if (!data.containsKey(path)) {
            line.setNewFrameValueCpu(0);
            line.frameTasks = null;
        }
        data.put(path, line);
        segmentStart = value;
        if (renderer != null) {
            int id = getUnusedTaskId();
            if (line.frameTasks == null) {
                line.frameTasks = new ArrayList<>();
                line.taskIds.add(line.frameTasks);
            }
            line.frameTasks.add(id);
            renderer.startProfiling(id);
            ongoingGpuProfiling = true;
        }
        prevPath = path;

    }

    private String getPath(String step, String... subPath) {
        StringBuilder path = new StringBuilder(step);
        if (subPath != null) {
            for (String s : subPath) {
                path.append("/").append(s);
            }
        }
        return path.toString();
    }

    @Override
    public void beginScope(String name) {
        if (data == null) {
            return;
        }
        String parent = scopes.isEmpty()
                ? curAppPath + (curVpPath == null ? "" : "/" + curVpPath) : scopes.peek();
        scopeContexts.push(new String[]{curAppPath, curVpPath, curSpPath, parent});
        scopeVpSteps.push(lastVpStep);
        scopes.push(parent + "/" + name);
        addStep(scopes.peek(), System.nanoTime());
    }

    @Override
    public void endScope() {
        if (scopes.isEmpty()) {
            return;
        }
        scopes.pop();
        String[] saved = scopeContexts.pop();
        curAppPath = saved[0];
        curVpPath = saved[1];
        curSpPath = saved[2];
        lastVpStep = scopeVpSteps.pop();
        String parent = saved[3];
        addStep(parent + "/Overhead", System.nanoTime());
    }

    public void setRenderer(Renderer renderer) {
        idsPool.clear();
        if (renderer != null && renderer.getCaps().contains(Caps.GpuTimerQuery)) {
            this.renderer = renderer;
            poolTaskIds(renderer);
        } else {
            this.renderer = null;
        }
    }

    private void poolTaskIds(Renderer renderer) {
        int[] ids = renderer.generateProfilingTasks(100);
        for (int id : ids) {
            idsPool.push(id);
        }
    }

    private int getUnusedTaskId() {
        if (idsPool.isEmpty()) {
            poolTaskIds(renderer);
        }

        return idsPool.pop();
    }

    public static class StatLine {
        private final long[] cpuTimes = new long[MAX_FRAMES];
        private final long[] gpuTimes = new long[MAX_FRAMES];
        private int startCursor = 0;
        private int cpuCursor = 0;
        private int gpuCursor = 0;
        private long cpuSum = 0;
        private long gpuSum = 0;
        private long lastValue = 0;
        private int nbFramesCpu;
        private int nbFramesGpu;
        List<List<Integer>> taskIds = new ArrayList<>();
        List<Integer> frameTasks;


        private StatLine(int currentFrame) {
            startCursor = currentFrame % MAX_FRAMES;
            cpuCursor = startCursor;
            gpuCursor = startCursor;
        }

        private void setNewFrameValueCpu(long value) {
            int newCursor = currentFrame % MAX_FRAMES;
            if (nbFramesCpu == 0) {
                startCursor = newCursor;
            }
            cpuCursor = newCursor;
            lastValue = value;
        }

        private void setValueCpu(long val) {
            lastValue = val;
        }

        private long getValueCpu() {
            return lastValue;
        }

        private void closeFrame() {
            if (isActive()) {
                cpuSum -= cpuTimes[cpuCursor];
                cpuTimes[cpuCursor] = lastValue;
                cpuSum += lastValue;
                nbFramesCpu++;
            } else {
                nbFramesCpu = 0;
            }
        }

        public void setValueGpu(long value) {
            gpuSum -= gpuTimes[gpuCursor];
            gpuTimes[gpuCursor] = value;
            gpuSum += value;
            nbFramesGpu++;
            gpuCursor = (gpuCursor + 1) % MAX_FRAMES;
        }

        public boolean isActive() {
            return cpuCursor >= currentFrame % MAX_FRAMES - 1;
        }

        public double getAverageCpu() {
            if (nbFramesCpu == 0) {
                return 0;
            }
            return cpuSum / (double) Math.min(nbFramesCpu, MAX_FRAMES);
        }

        public double getAverageGpu() {
            if (nbFramesGpu == 0) {
                return 0;
            }

            return gpuSum / (double) Math.min(nbFramesGpu, MAX_FRAMES);
        }
    }

}
