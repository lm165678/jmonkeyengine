package com.jme3.renderer.gpuscene;

import com.jme3.system.AppSettings;
import org.junit.Test;
import static org.junit.Assert.*;

public class GpuSceneSettingsTest {
    @Test
    public void oldSettingsWithoutNewKeysUseAutomaticPolicy() {
        AppSettings settings = new AppSettings(false);
        assertTrue(settings.isGpuSceneEnabled());
        assertTrue(settings.isGpuSceneBindlessEnabled());
    }

    @Test
    public void explicitOptOutSurvivesSettingsCopy() {
        AppSettings source = new AppSettings(true);
        source.setGpuSceneEnabled(false);
        source.setGpuSceneBindlessEnabled(false);
        AppSettings copy = new AppSettings(true);
        copy.copyFrom(source);
        assertFalse(copy.isGpuSceneEnabled());
        assertFalse(copy.isGpuSceneBindlessEnabled());
        copy.setGpuSceneEnabled(true);
        assertTrue(copy.isGpuSceneEnabled());
        assertFalse(copy.isGpuSceneBindlessEnabled());
    }
}
