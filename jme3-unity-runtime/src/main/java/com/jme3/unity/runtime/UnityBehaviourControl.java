package com.jme3.unity.runtime;

import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;
import com.jme3.util.clone.Cloner;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for imported behaviours. The scheduler owns message dispatch.
 * Loading, cloning and ordinary spatial updates are passive.
 * <pre>{@code
 * // 第一步：实现回调并把 Control 附到场景对象。
 * door.addControl(new UnityBehaviourControl() {
 *     @Override protected void onUpdate(float seconds) {
 *         getSpatial().move(seconds, 0, 0);
 *     }
 * });
 * // 第二步：在游戏线程使用 UnityBehaviourState 激活场景。
 * stateManager.attach(new UnityBehaviourState(rootNode));
 * }</pre>
 * Persisted subclasses need a public no-argument constructor.
 */
public abstract class UnityBehaviourControl extends AbstractControl {
    /** Authored activeSelf flag on imported game objects. */
    public static final String ACTIVE = "unity.active";
    /** Stable GameObject or Transform identities used by imported references. */
    public static final String FILE_ID = "unity.fileID";
    /** Serialized Unity Transform component ID. */
    public static final String TRANSFORM_ID = "unity.transformID";

    private Map<String, String> fields = new LinkedHashMap<>();
    private Map<String, Spatial> references = new LinkedHashMap<>();

    /** Sets a per-instance serialized field on the scene thread. */
    public final void setField(String name, String value) {
        if (value == null) {
            fields.remove(name);
        } else {
            fields.put(name, value);
        }
    }

    /** Resolves an authored object reference to an object in this instance. */
    public final void setReference(String name, Spatial value) {
        if (value == null) {
            references.remove(name);
        } else {
            references.put(name, value);
        }
    }

    protected final String stringField(String name, String fallback) {
        return fields.getOrDefault(name, fallback);
    }

    protected final float floatField(String name, float fallback) {
        return Float.parseFloat(stringField(name, Float.toString(fallback)));
    }

    protected final int intField(String name, int fallback) {
        return Integer.parseInt(stringField(name, Integer.toString(fallback)));
    }

    protected final boolean booleanField(String name, boolean fallback) {
        String value = stringField(name, Boolean.toString(fallback));
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    protected final Spatial objectField(String name) {
        return references.get(name);
    }

    final void shareFieldsWith(UnityBehaviourControl target) {
        target.fields = fields;
        target.references = references;
    }

    protected void onAwake() {
    }

    protected void onEnable() {
    }

    protected void onStart() {
    }

    protected void onUpdate(float seconds) {
    }

    protected void onLateUpdate(float seconds) {
    }

    protected void onFixedUpdate(float seconds) {
    }

    protected void onDisable() {
    }

    protected void onDestroy() {
    }

    @Override
    protected final void controlUpdate(float seconds) {
    }

    @Override
    protected final void controlRender(RenderManager manager, ViewPort viewPort) {
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        fields = new LinkedHashMap<>(fields);
        Map<String, Spatial> copies = new LinkedHashMap<>();
        references.forEach((key, value) -> copies.put(key, cloner.clone(value)));
        references = copies;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var capsule = exporter.getCapsule(this);
        capsule.write(fields.keySet().toArray(new String[0]), "fieldNames", null);
        capsule.write(fields.values().toArray(new String[0]), "fieldValues", null);
        capsule.writeStringSavableMap(references, "references", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var capsule = importer.getCapsule(this);
        String[] names = capsule.readStringArray("fieldNames", new String[0]);
        String[] values = capsule.readStringArray("fieldValues", new String[0]);
        if (names.length != values.length) {
            throw new IOException("Invalid Unity behaviour field table");
        }
        fields = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            fields.put(names[i], values[i]);
        }
        references = new LinkedHashMap<>();
        var loaded = capsule.readStringSavableMap("references", null);
        if (loaded != null) {
            loaded.forEach((name, spatial) -> references.put(name, (Spatial) spatial));
        }
    }
}
