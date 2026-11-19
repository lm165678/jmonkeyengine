package com.jme3.post;

import com.jme3.asset.AssetManager;
import com.jme3.export.*;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.clone.Cloner;
import java.io.*;
import java.util.*;

/**
 * Serializable scene-owned post-processing configuration, not GPU state.
 * SimpleApplication scene services apply the highest-priority attached settings
 * to the main viewport. Equal priorities use scene traversal order. Existing
 * application-owned FPPs take precedence; disabling scene services restores
 * manual ownership. Loading alone has no viewport side effects. Detached worlds
 * release their processor on the next update. Capture/GUI viewports are untouched.
 * Defaults: enabled, priority zero, no filters until configured. HDR uses RGBA16F
 * or RGBA32F; unsupported devices bypass the automatic chain.
 * Mutate settings and attach/detach scenes on the application thread.
 * Filter classes and their referenced assets must be shipped with the game.
 * <pre>{@code
 * // 第一步：创建配置；也可以直接加载 SDK 保存的 J3O，跳过配置步骤。
 * FilterPostProcessor chain = new FilterPostProcessor(assetManager);
 * chain.addFilter(new com.jme3.post.filters.FXAAFilter());
 * WorldSettings settings = new WorldSettings();
 * settings.setPostProcessing(chain, Collections.emptyMap()); // throws IOException
 * scene.addControl(settings);
 * // 第二步：保存 scene 后，普通加载并挂载会在下一帧自动启用主视口后处理。
 * rootNode.attachChild(assetManager.loadModel("Scenes/World.j3o"));
 * // 第三步：settings.setEnabled(false) 关闭；true 恢复；移除场景自动释放。
 * // 手动管理时先 getSceneServices().setEnabled(false)，再添加自己的 FPP。
 * // 非 SimpleApplication 可使用 WorldSettingsRuntime，在主线程每帧调用 sync。
 * }</pre>
 */
public final class WorldSettings extends AbstractControl {
    private byte[] postProcessing = new byte[0];
    private Map<String, Double> options = new LinkedHashMap<>();
    private int priority;
    private long revision;

    /** Snapshots filter configuration atomically; never retains live GPU objects. */
    public void setPostProcessing(FilterPostProcessor processor, Map<String, Double> editorOptions)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(processor, bytes);
        Map<String, Double> copy = new LinkedHashMap<>(editorOptions);
        postProcessing = bytes.toByteArray();
        options = copy;
        revision++;
    }

    /** Returns a fresh processor with independent filter histories for this viewport. */
    public FilterPostProcessor createPostProcessor(AssetManager assets) throws IOException {
        if (postProcessing.length == 0) {
            return null;
        }
        BinaryImporter importer = new BinaryImporter();
        importer.setAssetManager(assets);
        return (FilterPostProcessor) importer.load(new ByteArrayInputStream(postProcessing));
    }

    public Map<String, Double> getEditorOptions() {
        return Collections.unmodifiableMap(options);
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getRevision() {
        return revision;
    }

    @Override
    protected void controlUpdate(float tpf) {
    }

    @Override
    protected void controlRender(RenderManager manager, ViewPort view) {
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        postProcessing = postProcessing.clone();
        options = new LinkedHashMap<>(options);
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        OutputCapsule capsule = exporter.getCapsule(this);
        capsule.write(postProcessing, "postProcessing", new byte[0]);
        capsule.write(priority, "priority", 0);
        capsule.write(options.keySet().toArray(new String[0]), "optionNames", new String[0]);
        double[] values = new double[options.size()];
        int index = 0;
        for (double value : options.values()) {
            values[index++] = value;
        }
        capsule.write(values, "optionValues", new double[0]);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        InputCapsule capsule = importer.getCapsule(this);
        postProcessing = capsule.readByteArray("postProcessing", new byte[0]);
        priority = capsule.readInt("priority", 0);
        String[] names = capsule.readStringArray("optionNames", new String[0]);
        double[] values = capsule.readDoubleArray("optionValues", new double[0]);
        if (names.length != values.length) {
            throw new IOException("Invalid WorldSettings option data");
        }
        options = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            options.put(names[i], values[i]);
        }
    }
}
