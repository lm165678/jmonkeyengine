package com.jme3.unity.runtime;

import com.jme3.asset.ModelKey;
import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import com.jme3.export.Savable;
import com.jme3.material.Material;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.util.SafeArrayList;
import com.jme3.util.clone.Cloner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * A prefab resource key and ordered instance overrides. The expanded child is
 * rebuilt from the asset when J3O is loaded. Edit instance overrides through
 * this API on the owning scene thread.
 * <pre>{@code
 * // 第一步：加载 Prefab J3O 的独立实例并绑定资源键。
 * UnityPrefabInstance door = new UnityPrefabInstance("Prefabs/Door.j3o",
 *         assetManager.loadModel("Prefabs/Door.j3o"));
 * // 第二步：记录这扇门的实例覆盖，场景保存后仍会恢复。
 * door.addOverride(12L, "m_LocalPosition.x", "4", null);
 * door.applyOverrides();
 * rootNode.attachChild(door);
 * }</pre>
 */
public final class UnityPrefabInstance extends Node {
    private String assetPath;
    private List<OverrideValue> overrides = new ArrayList<>();
    private static final ThreadLocal<Set<String>> LOADING = ThreadLocal.withInitial(HashSet::new);

    private static final class OverrideValue {
        final long id;
        final String property;
        final String value;
        final Savable reference;

        OverrideValue(long id, String property, String value, Savable reference) {
            this.id = id;
            this.property = property;
            this.value = value;
            this.reference = reference;
        }
    }

    public UnityPrefabInstance() {
    }

    /** Uses a fresh instance of the given Prefab asset for immediate preview. */
    public UnityPrefabInstance(String assetPath, Spatial child) {
        super(child.getName());
        this.assetPath = assetPath;
        attachChild(child);
    }

    /** Records a supported override. References are a Material or a local fileID string. */
    public void addOverride(long id, String property, String value, Savable reference) {
        overrides.add(new OverrideValue(id, property, value, reference));
    }

    /** Applies the recorded overrides to the expanded instance before behaviour activation. */
    public void applyOverrides() {
        Map<Long, Spatial> objects = new HashMap<>();
        Map<Long, ImportedBehaviour> behaviours = new HashMap<>();
        for (Spatial child : getChildren()) {
            index(child, objects, behaviours);
        }
        Map<Spatial, Quaternion> rotations = new LinkedHashMap<>();
        for (OverrideValue override : overrides) {
            Spatial object = objects.get(override.id);
            ImportedBehaviour behaviour = behaviours.get(override.id);
            String property = override.property;
            if (behaviour != null) {
                if (property.equals("m_Enabled")) {
                    behaviour.setEnabled(asBoolean(override.value));
                } else if (property.startsWith("field:")) {
                    behaviour.setField(property.substring(6), override.value);
                } else if (property.startsWith("reference:")) {
                    behaviour.setReference(property.substring(10), objects.get(Long.parseLong(override.value)));
                }
            } else if (object == null) {
                Logger.getLogger(getClass().getName()).warning(
                        "Prefab override target " + override.id + " missing in " + assetPath);
            } else if (property.equals("m_Name")) {
                object.setName(override.value);
            } else if (property.equals("m_IsActive")) {
                boolean active = asBoolean(override.value);
                object.setUserData(UnityBehaviourControl.ACTIVE, active);
                object.setCullHint(active ? CullHint.Inherit : CullHint.Always);
            } else if (property.startsWith("material:") && override.reference instanceof Material) {
                List<Geometry> geometries = new ArrayList<>();
                object.depthFirstTraversal(spatial -> {
                    if (spatial instanceof Geometry) {
                        geometries.add((Geometry) spatial);
                    }
                });
                int materialIndex = Integer.parseInt(property.substring(9));
                if (materialIndex >= 0 && materialIndex < geometries.size()) {
                    Geometry geometry = geometries.get(materialIndex);
                    Material material = ((Material) override.reference).clone();
                    geometry.setMaterial(material);
                    geometry.setQueueBucket(material.isTransparent()
                            ? RenderQueue.Bucket.Transparent : RenderQueue.Bucket.Inherit);
                }
            } else if (property.startsWith("m_LocalPosition.") || property.startsWith("m_LocalScale.")) {
                boolean position = property.startsWith("m_LocalPosition.");
                Vector3f vector = position ? object.getLocalTranslation().clone() : object.getLocalScale().clone();
                int axis = "xyz".indexOf(property.charAt(property.length() - 1));
                if (axis >= 0) {
                    float value = Float.parseFloat(override.value);
                    vector.set(axis, position && axis == 2 ? -value : value);
                    if (position) {
                        object.setLocalTranslation(vector);
                    } else {
                        object.setLocalScale(vector);
                    }
                }
            } else if (property.startsWith("m_LocalRotation.")) {
                Quaternion q = rotations.computeIfAbsent(object, spatial -> spatial.getLocalRotation().clone());
                float value = Float.parseFloat(override.value);
                switch (property.charAt(property.length() - 1)) {
                    case 'x': q.set(-value, q.getY(), q.getZ(), q.getW()); break;
                    case 'y': q.set(q.getX(), -value, q.getZ(), q.getW()); break;
                    case 'z': q.set(q.getX(), q.getY(), value, q.getW()); break;
                    case 'w': q.set(q.getX(), q.getY(), q.getZ(), value); break;
                    default: break;
                }
            }
        }
        rotations.forEach((spatial, rotation) -> {
            if (rotation.norm() > 0) {
                spatial.setLocalRotation(rotation.normalizeLocal());
            }
        });
    }

    private static boolean asBoolean(String value) {
        return "1".equals(value) || Boolean.parseBoolean(value);
    }

    private static void index(Spatial spatial, Map<Long, Spatial> objects,
            Map<Long, ImportedBehaviour> behaviours) {
        Long objectId = spatial.getUserData(UnityBehaviourControl.FILE_ID);
        Long transformId = spatial.getUserData(UnityBehaviourControl.TRANSFORM_ID);
        for (Long id : new Long[]{objectId, transformId, spatial.getUserData("unity.rendererID")}) {
            if (id != null) {
                objects.put(id, spatial);
            }
        }
        for (int i = 0; i < spatial.getNumControls(); i++) {
            if (spatial.getControl(i) instanceof ImportedBehaviour) {
                ImportedBehaviour behaviour = (ImportedBehaviour) spatial.getControl(i);
                behaviours.put(behaviour.getFileId(), behaviour);
            }
        }
        if (spatial instanceof Node && !(spatial instanceof UnityPrefabInstance)) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                index(child, objects, behaviours);
            }
        }
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        overrides = new ArrayList<>(overrides);
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        SafeArrayList<Spatial> expanded = children;
        children = new SafeArrayList<>(Spatial.class);
        try {
            super.write(exporter);
        } finally {
            children = expanded;
        }
        var capsule = exporter.getCapsule(this);
        int size = overrides.size();
        long[] ids = new long[size];
        String[] properties = new String[size];
        String[] values = new String[size];
        Savable[] references = new Savable[size];
        for (int i = 0; i < size; i++) {
            OverrideValue value = overrides.get(i);
            ids[i] = value.id;
            properties[i] = value.property;
            values[i] = value.value;
            references[i] = value.reference;
        }
        capsule.write(assetPath, "prefab", null);
        capsule.write(ids, "targets", null);
        capsule.write(properties, "properties", null);
        capsule.write(values, "values", null);
        capsule.write(references, "references", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var capsule = importer.getCapsule(this);
        assetPath = capsule.readString("prefab", null);
        long[] ids = capsule.readLongArray("targets", new long[0]);
        String[] properties = capsule.readStringArray("properties", new String[0]);
        String[] values = capsule.readStringArray("values", new String[0]);
        Savable[] references = capsule.readSavableArray("references", new Savable[0]);
        if (assetPath == null || properties.length != ids.length || values.length != ids.length
                || references.length != ids.length) {
            throw new IOException("Invalid linked Unity prefab in " + getName());
        }
        overrides = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            addOverride(ids[i], properties[i], values[i], references[i]);
        }
        Set<String> loading = LOADING.get();
        if (!loading.add(assetPath)) {
            throw new IOException("Cyclic Unity prefab link " + assetPath);
        }
        try {
            attachChild(importer.getAssetManager().loadAsset(new ModelKey(assetPath)));
            applyOverrides();
        } catch (RuntimeException exception) {
            throw new IOException("Cannot restore " + getName() + " from " + assetPath, exception);
        } finally {
            loading.remove(assetPath);
            if (loading.isEmpty()) {
                LOADING.remove();
            }
        }
    }
}
