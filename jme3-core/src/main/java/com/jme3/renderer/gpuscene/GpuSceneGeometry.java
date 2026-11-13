package com.jme3.renderer.gpuscene;

import com.jme3.bounding.BoundingVolume;
import com.jme3.renderer.Renderer;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.util.BufferUtils;
import java.util.List;

/**
 * Internal draw packet retaining an ordinary material/technique. Produced by
 * GpuScenePipeline, never attached as a second copy to the scene graph. GPU storage
 * is owned by the pipeline; the attribute view must never be disposed independently.
 */
public final class GpuSceneGeometry extends Geometry {
    private final BufferObject commands;
    private final BufferObject transforms;
    private final VertexBuffer attribute = new VertexBuffer(VertexBuffer.Type.InstanceData);
    private final VertexBuffer[] attributes = {attribute};
    private final int lod;
    private final int commandIndex;
    private int[] vertexCounts;
    private int multiDrawCount;
    private BufferObject compacted;
    private BufferObject drawCount;

    void enableCompaction(BufferObject compacted, BufferObject drawCount) {
        this.compacted = compacted;
        this.drawCount = drawCount;
    }

    GpuSceneGeometry(Geometry source, BufferObject commands, BufferObject transforms, int lod, int commandIndex) {
        super(source.getName() + " GPU instances", source.getMesh());
        this.commands = commands;
        this.transforms = transforms;
        this.lod = lod;
        this.commandIndex = commandIndex;
        setIgnoreTransform(true);
        setMaterial(source.getMaterial().clone());
        getMaterial().setBoolean("UseInstancing", true);
        attribute.setupData(VertexBuffer.Usage.Stream, 16, VertexBuffer.Format.Float, BufferUtils.createFloatBuffer(16));
        attribute.setInstanced(true);
    }

    void enableMultiDraw(com.jme3.scene.Mesh packedMesh, int count, int[] vertexCounts) {
        setMesh(packedMesh);
        multiDrawCount = count;
        this.vertexCounts = vertexCounts;
    }

    void refresh(List<Geometry> instances) {
        worldBound = null;
        getWorldLightList().clear();
        for (int i = 0; i < instances.get(0).getWorldLightList().size(); i++) {
            getWorldLightList().add(instances.get(0).getWorldLightList().get(i));
        }
        for (Geometry geometry : instances) {
            BoundingVolume bound = geometry.getWorldBound();
            if (bound != null) {
                if (worldBound == null) {
                    worldBound = bound.clone();
                } else {
                    worldBound.mergeLocal(bound);
                }
            }
        }
    }

    // Bounds conservatively expand between rebuilds. This avoids scanning every
    // static instance when one moves; GPU per-instance culling remains exact to
    // its individual bounds. Rebuild recomputes a tight aggregate bound.
    void expandBound(BoundingVolume bound) {
        if (worldBound == null) {
            worldBound = bound.clone();
        } else {
            worldBound.mergeLocal(bound);
        }
    }

    void shareBoundsAndLights(GpuSceneGeometry first) {
        worldBound = first.worldBound;
        getWorldLightList().clear();
        for (int i = 0; i < first.getWorldLightList().size(); i++) {
            getWorldLightList().add(first.getWorldLightList().get(i));
        }
    }

    /** Called by ordinary technique logic after shader, lights and render states are bound. */
    public void draw(Renderer renderer) {
        if (attribute.getId() != transforms.getId()) {
            attribute.resetObject();
            attribute.setId(transforms.getId());
        }
        attribute.clearUpdateNeeded();
        if (compacted != null) {
            renderer.renderMeshCompactedIndirect(getMesh(), compacted, drawCount, commands,
                    multiDrawCount, attributes, vertexCounts);
        } else if (multiDrawCount > 0) {
            renderer.renderMeshMultiDrawIndirect(getMesh(), commands, 0, multiDrawCount, attributes, vertexCounts);
        } else {
            renderer.renderMeshIndirect(getMesh(), lod, commands, commandIndex * 20L, attributes);
        }
    }
}
