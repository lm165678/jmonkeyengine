package com.jme3.post.filters;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.post.FrameGraphFilter;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.texture.Image;

/** Shared capability negotiation for graph-native HDR filters. */
abstract class HdrGraphFilter extends FrameGraphFilter {
    protected AssetManager assets;
    protected RenderManager manager;
    protected ViewPort view;
    protected int width;
    protected int height;
    protected Image.Format format;

    HdrGraphFilter(String name) {
        super(name);
    }

    @Override
    protected final void initFilter(AssetManager assets, RenderManager manager, ViewPort view, int width, int height) {
        this.assets = assets;
        this.manager = manager;
        this.view = view;
        this.width = width;
        this.height = height;
        var caps = manager.getRenderer().getCaps();
        format = caps.contains(Caps.HalfFloatColorBufferRGBA) ? Image.Format.RGBA16F
                : caps.contains(Caps.FloatColorBufferRGBA) ? Image.Format.RGBA32F : null;
        if (!caps.contains(Caps.GLSL150) && !caps.contains(Caps.GLSL300)) {
            format = null;
        }
        initialize();
    }

    protected abstract void initialize();

    protected Material shader(String name) {
        return new Material(assets, "Common/MatDefs/Post/Cinematic/" + name + ".j3md");
    }

    protected static float bounded(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be finite in [" + min + "," + max + "]");
        }
        return value;
    }
}
