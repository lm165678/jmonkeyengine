package com.jme3.renderer.gpuscene;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.texture.*;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import java.util.*;

/** Current-frame standard-depth max pyramid. Owned by one viewport GPU Scene. */
final class HiZPyramid implements AutoCloseable {
    final List<Texture2D> levels = new ArrayList<>();
    final List<FrameBuffer> targets = new ArrayList<>();
    final Material reduce;
    final Geometry quad = new Geometry("HiZ reduction", new Quad(1, 1));
    final int width;
    final int height;

    HiZPyramid(AssetManager assets, int width, int height) {
        this.width = width;
        this.height = height;
        reduce = new Material(assets, "Common/MatDefs/GpuScene/Reduce.j3md");
        quad.setMaterial(reduce);
        Texture2D depth = new Texture2D(width, height, Image.Format.Depth24);
        configure(depth);
        FrameBuffer base = new FrameBuffer(width, height, 1);
        base.setDepthTexture(depth);
        levels.add(depth);
        targets.add(base);
        while ((width > 1 || height > 1) && levels.size() < 12) {
            width = Math.max(1, (width + 1) / 2);
            height = Math.max(1, (height + 1) / 2);
            Texture2D texture = new Texture2D(width, height, Image.Format.R32F);
            configure(texture);
            FrameBuffer target = new FrameBuffer(width, height, 1);
            target.setColorTexture(texture);
            levels.add(texture);
            targets.add(target);
        }
    }

    private void configure(Texture2D texture) {
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setWrap(Texture.WrapMode.EdgeClamp);
    }

    void reduce(RenderManager manager) {
        Renderer renderer = manager.getRenderer();
        for (int i = 1; i < levels.size(); i++) {
            FrameBuffer target = targets.get(i);
            renderer.setFrameBuffer(target);
            renderer.setViewPort(0, 0, target.getWidth(), target.getHeight());
            reduce.setTexture("Previous", levels.get(i - 1));
            reduce.render(quad, manager);
        }
    }

    void bind(Material material) {
        material.setInt("HiZLevels", levels.size());
        for (int i = 0; i < 12; i++) {
            material.setTexture("HiZ" + i, levels.get(Math.min(i, levels.size() - 1)));
        }
    }

    @Override
    public void close() {
        for (FrameBuffer target : targets) {
            target.dispose();
        }
        for (Texture2D texture : levels) {
            texture.getImage().dispose();
        }
    }
}
