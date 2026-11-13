package com.jme3.renderer;

import com.jme3.renderer.opengl.GL4;
import com.jme3.renderer.opengl.GLRenderer;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProfilingQueryLifecycleTest {
    @Test
    public void pipelineCanReleaseQueriesAfterRendererDestroyedTheContext() {
        boolean[] contextAlive = {true};
        int[] deleted = {0};
        GL4 gl = (GL4) Proxy.newProxyInstance(GL4.class.getClassLoader(), new Class<?>[]{GL4.class},
                (proxy, method, args) -> {
                    if (!contextAlive[0]) {
                        throw new AssertionError("GL call after context destruction: " + method.getName());
                    }
                    if (method.getName().equals("glGenQueries")) {
                        IntBuffer ids = (IntBuffer) args[1];
                        for (int i = 0; i < (Integer) args[0]; i++) {
                            ids.put(i, i + 1);
                        }
                    } else if (method.getName().equals("glDeleteQueries")) {
                        deleted[0] += ((IntBuffer) args[0]).remaining();
                    } else if (method.getName().equals("glGetInteger")) {
                        ((IntBuffer) args[1]).put(0, 0);
                    }
                    return null;
                });
        GLRenderer renderer = new GLRenderer(gl, null, null);
        renderer.getCaps().add(Caps.GpuTimerQuery);
        int[] tasks = renderer.generateProfilingTasks(3);
        renderer.cleanup();
        assertEquals(3, deleted[0]);
        contextAlive[0] = false;
        renderer.deleteProfilingTasks(tasks);
        renderer.stopProfiling();
        assertEquals(3, deleted[0]);
    }
}
