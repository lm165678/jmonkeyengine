package com.jme3.app;

import com.jme3.profile.AppStep;
import com.jme3.profile.VpStep;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import org.junit.Test;
import static org.junit.Assert.*;

public class DetailedProfilerTest {
    @Test
    public void delayedGpuSegmentsAreSummedPerFrameEvenWhenPassDisappears() {
        class Timers extends com.jme3.system.NullRenderer {
            int next;
            boolean ready;
            @Override
            public int[] generateProfilingTasks(int count) {
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) {
                    ids[i] = ++next;
                }
                return ids;
            }
            @Override
            public boolean isTaskResultAvailable(int id) {
                return ready;
            }
            @Override
            public long getProfilingTime(int id) {
                assertTrue(ready);
                return 100;
            }
        }
        Timers renderer = new Timers();
        DetailedProfiler profiler = new DetailedProfiler();
        profiler.setRenderer(renderer);
        profiler.appStep(AppStep.BeginFrame);
        profiler.beginScope("Repeated");
        profiler.endScope();
        profiler.beginScope("Repeated");
        profiler.endScope();
        profiler.appStep(AppStep.EndFrame);
        DetailedProfiler.StatLine repeated = profiler.getStats().get("BeginFrame/Repeated");
        profiler.appStep(AppStep.BeginFrame);
        profiler.appStep(AppStep.EndFrame);
        assertEquals(0, repeated.getAverageGpu(), 0);
        renderer.ready = true;
        profiler.appStep(AppStep.BeginFrame);
        assertEquals(200, repeated.getAverageGpu(), 0);
        profiler.appStep(AppStep.EndFrame);
    }
    @Test
    public void scopesRetainPassAndLegacyDetailsAndRestoreViewport() {
        DetailedProfiler profiler = new DetailedProfiler();
        ViewPort viewport = new ViewPort("Main", new Camera(80, 60));
        profiler.appStep(AppStep.BeginFrame);
        profiler.appStep(AppStep.RenderMainViewPorts);
        profiler.vpStep(VpStep.BeginRender, viewport, null);
        profiler.beginScope("FrameGraph");
        profiler.beginScope("Passes/Shadow");
        profiler.vpStep(VpStep.RenderScene, viewport, null);
        profiler.endScope();
        profiler.beginScope("Passes/Lighting");
        profiler.endScope();
        profiler.endScope();
        profiler.appStep(AppStep.EndFrame);
        var data = profiler.getStats();
        String base = "RenderMainViewPorts/Main/BeginRender/FrameGraph";
        assertTrue(data.containsKey(base + "/Passes/Shadow"));
        assertTrue(data.keySet().stream().anyMatch(path -> path.startsWith(base + "/Passes/Shadow/")
                && path.endsWith("RenderScene")));
        assertTrue(data.containsKey(base + "/Passes/Lighting"));
        assertTrue(data.get(base + "/Overhead").getAverageCpu() >= 0);
        assertTrue(profiler.getAverageFrameTime() > 0);
    }
}
