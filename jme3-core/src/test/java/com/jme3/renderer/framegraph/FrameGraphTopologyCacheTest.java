package com.jme3.renderer.framegraph;

import com.jme3.renderer.*;
import com.jme3.system.NullRenderer;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameGraphTopologyCacheTest {
    private final RenderManager manager = new RenderManager(new NullRenderer());
    private final ViewPort viewport = new ViewPort("cache", new Camera(80, 60));

    private long count(FrameGraph graph, String name) throws Exception {
        java.lang.reflect.Field field = FrameGraph.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(graph);
    }

    @Test
    public void repeatedExpansionsUseCurrentCallbacksAndSuppliers() throws Exception {
        try (FrameGraph graph = new FrameGraph()) {
            for (int frame = 0; frame < 30; frame++) {
                graph.beginFrame();
                int value = frame;
                StringBuilder output = new StringBuilder();
                graph.addResourceExpansionPass("expand", expanded -> {
                    ResourceHandle<StringBuilder> external = expanded.importResource("out",
                            StringBuilder.class, () -> output);
                    expanded.addPass("write", pass -> pass.get(external).append(value)).write(external);
                    expanded.addResourceExpansionPass("nested", nested -> {
                        nested.addPass("check", pass -> assertEquals("" + value, output.toString())).sideEffect();
                    });
                });
                graph.addPass("after", pass -> assertEquals("" + value, output.toString())).sideEffect();
                graph.execute(manager, viewport);
                assertEquals("" + frame, output.toString());
            }
            assertTrue(count(graph, "topologyHits") >= 29);
            assertTrue(count(graph, "topologyMisses") <= 4);
        }
    }

    @Test
    public void versionAntiDependenciesSurviveCacheAndChangedDeclarationsRevalidate() throws Exception {
        try (FrameGraph graph = new FrameGraph()) {
            for (int frame = 0; frame < 5; frame++) {
                graph.beginFrame();
                StringBuilder output = new StringBuilder();
                ResourceHandle<StringBuilder> first = graph.importResource("out", StringBuilder.class, () -> output);
                ResourceHandle<StringBuilder> next = graph.nextVersion("next", first);
                graph.addPass("overwrite", pass -> pass.get(next).append("b")).write(next);
                graph.addPass("read old", pass -> assertEquals("a", pass.get(first).toString())).read(first).sideEffect();
                graph.addPass("first", pass -> pass.get(first).append("a")).write(first);
                graph.exportResource(next);
                graph.execute(manager, viewport);
                assertEquals("ab", output.toString());
                assertEquals(Arrays.asList("first", "read old", "overwrite"), graph.getExecutionOrder());
            }
            assertTrue(count(graph, "topologyHits") >= 4);
            graph.beginFrame();
            ResourceHandle<StringBuilder> output = graph.importResource("out", StringBuilder.class, StringBuilder::new);
            graph.addPass("one", pass -> { }).write(output);
            graph.addPass("two", pass -> { }).write(output);
            try {
                graph.compile();
                fail("Multiple writers must still fail after a cache hit");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Multiple writers"));
            }
        }
    }

    @Test
    public void cachedLifetimePlanDoesNotReuseOldDefinitionsOrViewportSize() throws Exception {
        try (FrameGraph graph = new FrameGraph()) {
            for (int frame = 1; frame <= 5; frame++) {
                int expected = frame;
                graph.beginFrame();
                viewport.getCamera().resize(80 * frame, 60 * frame, false);
                ResourceDefinition<int[]> definition = new ResourceDefinition<int[]>() {
                    public Class<int[]> getType() {
                        return int[].class;
                    }
                    public int[] create(Renderer renderer, int width, int height) {
                        return new int[]{expected, width, height};
                    }
                    public void destroy(int[] value) {
                    }
                };
                ResourceHandle<int[]> handle = graph.createResource("target", definition);
                graph.addPass("write", pass -> assertArrayEquals(
                        new int[]{expected, 80 * expected, 60 * expected}, pass.get(handle))).write(handle);
                graph.exportResource(handle);
                graph.execute(manager, viewport);
            }
            assertEquals(4, count(graph, "topologyHits"));
        }
    }

    @Test
    public void accessIntentChangeCannotReuseValidatedStorageFeedbackPlan() {
        try (FrameGraph graph = new FrameGraph()) {
            graph.beginFrame();
            ResourceHandle<StringBuilder> output = graph.importResource("out", StringBuilder.class, StringBuilder::new);
            graph.addPass("update", pass -> { }).read(output, ResourceAccess.STORAGE_BUFFER)
                    .write(output, ResourceAccess.STORAGE_BUFFER);
            graph.compile();
            graph.beginFrame();
            output = graph.importResource("out", StringBuilder.class, StringBuilder::new);
            graph.addPass("update", pass -> { }).read(output, ResourceAccess.SAMPLED_TEXTURE)
                    .write(output, ResourceAccess.FRAMEBUFFER);
            try {
                graph.compile();
                fail("Sampling feedback must be revalidated");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("feedback"));
            }
        }
    }
}
