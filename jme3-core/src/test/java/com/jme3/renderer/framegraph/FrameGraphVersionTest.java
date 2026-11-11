package com.jme3.renderer.framegraph;

import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.system.NullRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameGraphVersionTest {
    private final List<Integer> barriers = new ArrayList<>();
    private final Renderer renderer = new NullRenderer() {
        @Override
        public void memoryBarrier(int mask) {
            barriers.add(mask);
        }
    };
    private final RenderManager manager = new RenderManager(renderer);
    private final ViewPort viewport = new ViewPort("Versions", new Camera(80, 60));

    private static class Definition implements ResourceDefinition<StringBuilder> {
        int created;
        int destroyed;

        public Class<StringBuilder> getType() {
            return StringBuilder.class;
        }

        public StringBuilder create(Renderer renderer, int width, int height) {
            created++;
            return new StringBuilder();
        }

        public void destroy(StringBuilder value) {
            destroyed++;
        }
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            fail("Expected rejection");
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // Invalid declarations must fail before submitting unsafe GPU work.
        }
    }

    @Test
    public void imageStorageSynchronizesSamplingAndFramebufferConsumers() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> image = graph.createResource("Image", new Definition());
            graph.addPass("ImageStore", context -> {}).write(image, ResourceAccess.STORAGE_IMAGE);
            graph.addPass("Sample", context -> {}).read(image, ResourceAccess.SAMPLED_TEXTURE).sideEffect();
            graph.addPass("Blit", context -> {}).read(image, ResourceAccess.FRAMEBUFFER).sideEffect();
            graph.execute(manager, viewport);
            assertEquals(Arrays.asList(0x20, 0x8, 0x400, -1), barriers);
        }
    }

    @Test
    public void versionHazardsCannotBeOverriddenByConflictingExplicitOrder() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> first = graph.createResource("First", new Definition());
            ResourceHandle<StringBuilder> next = graph.nextVersion("Next", first);
            graph.addPass("Init", context -> {}).write(first);
            FrameGraph.PassBuilder update = graph.addPass("Update", context -> {}).write(next);
            graph.addPass("TooLate", context -> {}).read(first).after(update).sideEffect();
            rejects(graph::compile);
        }
    }

    @Test
    public void importIdentityCannotChangeTypeOrStealAnExistingName() {
        try (FrameGraph graph = new FrameGraph()) {
            Object token = new Object();
            graph.importResource("First", StringBuilder.class, token, StringBuilder::new);
            graph.createResource("Occupied", new Definition());
            rejects(() -> graph.importResource("OtherType", String.class, token, () -> ""));
            rejects(() -> graph.importResource("Occupied", StringBuilder.class, token, StringBuilder::new));
        }
    }

    @Test
    public void externalSupplierIsStableAcrossPlanningBoundariesAndVersions() {
        int[] calls = {0};
        StringBuilder value = new StringBuilder();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> initial = graph.importResource("External", StringBuilder.class, () -> {
                calls[0]++;
                return value;
            });
            graph.addPass("First", context -> context.get(initial).append("a")).write(initial);
            graph.addResourceExpansionPass("Later", planned -> {
                ResourceHandle<StringBuilder> next = planned.nextVersion("Next", initial);
                planned.addPass("Update", context -> context.get(next).append("b")).write(next);
            });
            graph.execute(manager, viewport);
            assertEquals("ab", value.toString());
            assertEquals(1, calls[0]);
        }
    }

    @Test
    public void persistentVersionsRetainContentsAcrossRebuilds() {
        Definition definition = new Definition();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> initial = graph.findOrCreatePersistentResource("History", definition);
            ResourceHandle<StringBuilder> next = graph.nextVersion("Next", initial);
            graph.addPass("Init", context -> context.get(initial).append("one")).write(initial);
            graph.addPass("Update", context -> context.get(next).append("two")).write(next);
            graph.execute(manager, viewport);
            graph.beginFrame();
            ResourceHandle<StringBuilder> history = graph.findOrCreatePersistentResource("History", definition);
            graph.addPass("Read", context -> assertEquals("onetwo", context.get(history).toString()))
                    .read(history).sideEffect();
            graph.execute(manager, viewport);
            assertEquals(1, definition.created);
        }
        assertEquals(1, definition.destroyed);
    }

    @Test
    public void versionsPreserveStorageAndOrderOldReadersBeforeNewWrites() {
        Definition definition = new Definition();
        List<String> calls = new ArrayList<>();
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> first = graph.createResource("First", definition);
            ResourceHandle<StringBuilder> next = graph.nextVersion("Next", first);
            graph.addPass("Update", context -> {
                calls.add("update");
                context.get(next).append("+new");
            }).write(next);
            graph.addPass("OldReader", context -> {
                assertEquals("old", context.get(first).toString());
                calls.add("read");
            }).read(first).sideEffect();
            graph.addPass("Initialize", context -> context.get(first).append("old")).write(first);
            graph.exportResource(next);
            graph.execute(manager, viewport);
            assertEquals(Arrays.asList("read", "update"), calls);
            assertEquals("old+new", graph.getExported(next).toString());
            assertEquals(1, definition.created);
            assertEquals(0, graph.trimUnusedResources(0));
            graph.beginFrame();
            assertEquals(1, graph.trimUnusedResources(0));
        }
        assertEquals(1, definition.destroyed);
    }

    @Test
    public void canonicalAliasesShareSupplierAndDependencies() {
        Object identity = new Object();
        int[] queries = {0};
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> a = graph.importResource("A", StringBuilder.class, identity, () -> {
                queries[0]++;
                return new StringBuilder();
            });
            ResourceHandle<StringBuilder> b = graph.importResource("B", StringBuilder.class, identity, () -> {
                throw new AssertionError("Secondary alias supplier must not be used");
            });
            assertSame(a, b);
            assertSame(a, graph.findResource("B", StringBuilder.class));
            graph.addPass("Read", context -> assertEquals("ok", context.get(b).toString())).read(b).sideEffect();
            graph.addPass("Write", context -> context.get(a).append("ok")).write(a);
            graph.execute(manager, viewport);
            assertEquals(1, queries[0]);
            rejects(() -> graph.nextVersion("Late", a));
        }
    }

    @Test
    public void rejectsBranchingOldExportsAndTextureFeedback() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> first = graph.createResource("First", new Definition());
            ResourceHandle<StringBuilder> next = graph.nextVersion("Next", first);
            rejects(() -> graph.nextVersion("Branch", first));
            rejects(() -> graph.exportResource(first));
            graph.addPass("Init", context -> {}).write(first);
            graph.addPass("Bad", context -> {}).read(first, ResourceAccess.SAMPLED_TEXTURE).write(next);
            rejects(graph::compile);
        }
    }

    @Test
    public void storageWritesSynchronizeConsumersAndGraphBoundary() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> value = graph.createResource("Buffer", new Definition());
            graph.addPass("Compute", context -> context.get(value).append("written"))
                    .write(value, ResourceAccess.STORAGE_BUFFER);
            graph.addPass("Vertex", context -> assertEquals("written", context.get(value).toString()))
                    .read(value, ResourceAccess.VERTEX_BUFFER).sideEffect();
            graph.addPass("Readback", context -> context.get(value))
                    .read(value, ResourceAccess.BUFFER_TRANSFER).sideEffect();
            graph.execute(manager, viewport);
            assertEquals(Arrays.asList(0x2000, 0x1, 0x200, -1), barriers);
        }
    }

    @Test
    public void coherentRasterPassesDoNotRequireNewBackendCapabilities() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> value = graph.createResource("Color", new Definition());
            graph.addPass("Raster", context -> {}).write(value, ResourceAccess.FRAMEBUFFER);
            graph.addPass("Sample", context -> {}).read(value, ResourceAccess.SAMPLED_TEXTURE).sideEffect();
            graph.execute(manager, viewport);
            assertTrue(barriers.isEmpty());
        }
    }

    @Test
    public void failedStorageProducerStillPublishesAndReleasesResources() {
        try (FrameGraph graph = new FrameGraph()) {
            ResourceHandle<StringBuilder> value = graph.createResource("Buffer", new Definition());
            graph.addPass("Failure", context -> {
                throw new IllegalStateException("After GPU submission");
            }).write(value, ResourceAccess.STORAGE_BUFFER).sideEffect();
            rejects(() -> graph.execute(manager, viewport));
            assertEquals(Arrays.asList(0x2000, -1), barriers);
            assertEquals(1, graph.trimUnusedResources(0));
        }
    }

    @Test
    public void aliasedMultipleWritersAreRejected() {
        try (FrameGraph graph = new FrameGraph()) {
            Object identity = new Object();
            ResourceHandle<StringBuilder> a = graph.importResource("A", StringBuilder.class, identity, StringBuilder::new);
            ResourceHandle<StringBuilder> b = graph.importResource("B", StringBuilder.class, identity, StringBuilder::new);
            graph.addPass("A", context -> {}).write(a);
            graph.addPass("B", context -> {}).write(b);
            rejects(graph::compile);
        }
    }
}
