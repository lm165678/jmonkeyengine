package com.jme3.post.filters;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExposureFilterTest {
    @Test
    void defaultsValidationAndPersistence() {
        ExposureFilter filter = new ExposureFilter();
        assertEquals(0f, filter.getExposureCompensation());
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -17, 17}) {
            assertThrows(IllegalArgumentException.class, () -> filter.setExposureCompensation(invalid));
        }
        filter.setExposureCompensation(-2.5f);
        filter.setEnabled(false);
        ExposureFilter copy = BinaryExporter.saveAndLoad(new DesktopAssetManager(), filter);
        assertEquals(-2.5f, copy.getExposureCompensation());
        assertFalse(copy.isEnabled());
        assertEquals(0f, BinaryExporter.saveAndLoad(new DesktopAssetManager(), new ExposureFilter()).getExposureCompensation());
    }
}
