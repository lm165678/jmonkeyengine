package com.jme3.unity.runtime;

import com.jme3.export.JmeExporter;
import com.jme3.export.JmeImporter;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serialized script binding. An editor can load and save this binding without
 * having compiled the game's generated classes. Only the game scheduler resolves it.
 */
public final class ImportedBehaviour extends UnityBehaviourControl {
    private String source;
    private String className;
    private long fileId;

    public ImportedBehaviour() {
    }

    /** Creates an inert binding. A null class name preserves unsupported script data. */
    public ImportedBehaviour(String source, String className, long fileId) {
        this.source = source;
        this.className = className;
        this.fileId = fileId;
    }

    /** Returns the local component identity used by prefab overrides. */
    public long getFileId() {
        return fileId;
    }

    UnityBehaviourControl instantiate(ClassLoader loader) {
        if (className == null) {
            return null;
        }
        try {
            Class<?> type = Class.forName(className, false, loader);
            UnityBehaviourControl control = type.asSubclass(UnityBehaviourControl.class)
                    .getConstructor().newInstance();
            shareFieldsWith(control);
            control.setSpatial(getSpatial());
            return control;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError exception) {
            Logger.getLogger(ImportedBehaviour.class.getName()).log(Level.WARNING,
                    "Could not activate Unity script " + source + " (" + className + ")", exception);
            return null;
        }
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        var capsule = exporter.getCapsule(this);
        capsule.write(source, "source", null);
        capsule.write(className, "className", null);
        capsule.write(fileId, "fileId", 0L);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        var capsule = importer.getCapsule(this);
        source = capsule.readString("source", null);
        className = capsule.readString("className", null);
        fileId = capsule.readLong("fileId", 0L);
    }
}
