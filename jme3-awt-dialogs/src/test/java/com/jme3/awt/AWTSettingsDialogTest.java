package com.jme3.awt;

import com.jme3.system.AppSettings;
import java.awt.DisplayMode;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.UUID;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AWTSettingsDialogTest {
    private String preferenceKey;
    private AWTSettingsDialog dialog;

    @BeforeEach
    void requireDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        preferenceKey = "jme-dialog-test-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanup() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (dialog != null) {
                dialog.dispose();
            }
        });
        if (preferenceKey != null) {
            Preferences.userRoot().node(preferenceKey).removeNode();
        }
    }

    @Test
    void windowedSelectionReplacesSavedWindowSize() throws Exception {
        verifySelectionRoundTrip(false);
    }

    @Test
    void hdrWindowedSelectionReplacesSavedWindowSize() throws Exception {
        verifySelectionRoundTrip(true);
    }

    @Test
    void initialSelectionUsesWindowSizeInsteadOfScaledFramebuffer() throws Exception {
        AppSettings settings = settings();
        settings.setWindowSize(1280, 720);
        settings.setResolution(2560, 1440);
        open(settings, false);
        SwingUtilities.invokeAndWait(() -> assertEquals("1280 x 720", resolutions().getSelectedItem()));
    }

    @Test
    void legacySettingsWithoutWindowSizeStillUseResolution() throws Exception {
        AppSettings settings = settings();
        settings.setResolution(1280, 720);
        open(settings, false);
        SwingUtilities.invokeAndWait(() -> assertEquals("1280 x 720", resolutions().getSelectedItem()));
    }

    @Test
    void otherWindowedOptionsPersistAndRestoreInBothDirections() throws Exception {
        for (boolean enabled : new boolean[] {true, false}) {
            AppSettings settings = settings();
            open(settings, false);
            SwingUtilities.invokeAndWait(() -> {
                resolutions().setSelectedItem("1280 x 720");
                check("vsyncBox", enabled);
                check("gammaBox", enabled);
                check("hdrDisplayOutputBox", enabled);
                check("x11PlatformPreferredBox", enabled);
                combo("rendererCombo").setSelectedItem(enabled
                        ? AppSettings.LWJGL_OPENGL45 : AppSettings.ANGLE_GLES3);
                combo("colorDepthCombo").setSelectedItem(enabled ? "32 bpp" : "16 bpp");
                combo("antialiasCombo").setSelectedItem(enabled ? "8x" : "2x");
                combo("displayScaleModeCombo").setSelectedIndex(enabled ? 4 : 0);
                save();
                dialog.dispose();
            });
            AppSettings restored = settings();
            open(restored, true);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(enabled, restored.isVSync());
                assertEquals(enabled, restored.isGammaCorrection());
                assertEquals(enabled, restored.isHdrDisplayOutput());
                assertEquals(enabled, restored.isX11PlatformPreferred());
                assertEquals(enabled ? AppSettings.LWJGL_OPENGL45 : AppSettings.ANGLE_GLES3,
                        restored.getRenderer());
                assertEquals(enabled ? 32 : 16, restored.getBitsPerPixel());
                assertEquals(enabled ? 8 : 2, restored.getSamples());
                assertEquals(enabled ? 3f : 0f, restored.getDisplayScaleMode());
                assertEquals(-1, restored.getFrequency());
                assertEquals(enabled, ((JCheckBox) field("vsyncBox")).isSelected());
                assertEquals(enabled, ((JCheckBox) field("gammaBox")).isSelected());
                assertEquals(enabled, ((JCheckBox) field("hdrDisplayOutputBox")).isSelected());
                assertEquals(enabled ? "8x" : "2x", combo("antialiasCombo").getSelectedItem());
                dialog.dispose();
            });
        }
    }

    @Test
    void customScaleAndSampleCountAreNotChangedOnOpen() throws Exception {
        AppSettings settings = settings();
        settings.setSamples(12);
        settings.setDisplayScaleMode(2.5f);
        open(settings, false);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("12x", combo("antialiasCombo").getSelectedItem());
            save();
            assertEquals(12, settings.getSamples());
            assertEquals(2.5f, settings.getDisplayScaleMode());
        });
    }

    @Test
    void fullscreenModesOnlyOfferRefreshRatesForTheirResolution() throws Exception {
        AppSettings settings = settings();
        open(settings, false);
        SwingUtilities.invokeAndWait(() -> {
            setField("modes", new DisplayMode[] {
                    new DisplayMode(1280, 720, 32, 60),
                    new DisplayMode(1280, 720, 32, 120),
                    new DisplayMode(1920, 1080, 32, 144)
            });
            ((JCheckBox) field("fullscreenBox")).doClick();
            assertFalse(resolutions().isEnabled());
            assertFalse(combo("displayFreqCombo").isEnabled());
            combo("fullscreenModeCombo").setSelectedIndex(1);
            assertTrue(resolutions().isEnabled());
            assertTrue(combo("displayFreqCombo").isEnabled());
            resolutions().setSelectedItem("1280 x 720");
            JComboBox<?> frequencies = combo("displayFreqCombo");
            assertEquals(2, frequencies.getItemCount());
            assertEquals("60 Hz", frequencies.getItemAt(0));
            assertEquals("120 Hz", frequencies.getItemAt(1));
            frequencies.setSelectedItem("120 Hz");
            save();
            assertTrue(settings.isFullscreen());
            assertEquals(AppSettings.FULLSCREEN_MODE_EXCLUSIVE_FULLSCREEN, settings.getFullscreenMode());
            assertEquals(120, settings.getFrequency());
            AppSettings persisted = settings();
            try {
                persisted.load(preferenceKey);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            assertEquals(120, persisted.getFrequency());
            assertEquals(settings.getFullscreenMode(), persisted.getFullscreenMode());
            resolutions().setSelectedItem("1920 x 1080");
            assertEquals(1, frequencies.getItemCount());
            assertEquals("144 Hz", frequencies.getSelectedItem());
            combo("fullscreenModeCombo").setSelectedIndex(0);
            assertFalse(resolutions().isEnabled());
            assertFalse(frequencies.isEnabled());
            save();
            assertEquals(AppSettings.FULLSCREEN_MODE_BORDERLESS_WINDOW, settings.getFullscreenMode());
            assertEquals(-1, settings.getFrequency());
        });
    }

    private void verifySelectionRoundTrip(boolean hdr) throws Exception {
        AppSettings settings = settings();
        settings.setResolution(1280, 720);
        settings.setWindowSize(1440, 900);
        settings.setHdrDisplayOutput(hdr);
        settings.save(preferenceKey);
        open(settings, true);
        SwingUtilities.invokeAndWait(() -> {
            resolutions().setSelectedItem("1024 x 768");
            ((JCheckBox) field("fullscreenBox")).setSelected(false);
            save();
            assertSize(settings, 1024, 768);
            dialog.dispose();
        });

        AppSettings restored = settings();
        open(restored, true);
        SwingUtilities.invokeAndWait(() -> {
            assertSize(restored, 1024, 768);
            assertEquals("1024 x 768", resolutions().getSelectedItem());
            assertEquals(hdr, restored.isHdrDisplayOutput());
            assertFalse(restored.isFullscreen());
        });
    }

    private AppSettings settings() {
        AppSettings settings = new AppSettings(true);
        settings.setTitle(preferenceKey);
        settings.setFullscreen(false);
        return settings;
    }

    private void open(AppSettings settings, boolean load) throws Exception {
        SwingUtilities.invokeAndWait(() -> dialog = new AWTSettingsDialog(settings, (URL) null, load));
        // Let the dialog populate its choices on the EDT before interacting.
        SwingUtilities.invokeAndWait(() -> { });
    }

    private JComboBox<?> resolutions() {
        return combo("displayResCombo");
    }

    private JComboBox<?> combo(String name) {
        return (JComboBox<?>) field(name);
    }

    private void check(String name, boolean selected) {
        ((JCheckBox) field(name)).setSelected(selected);
    }

    private void save() {
        try {
            Method save = AWTSettingsDialog.class.getDeclaredMethod("verifyAndSaveCurrentSelection");
            save.setAccessible(true);
            assertEquals(Boolean.TRUE, save.invoke(dialog));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private void setField(String name, Object value) {
        try {
            Field field = AWTSettingsDialog.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(dialog, value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private Object field(String name) {
        try {
            Field field = AWTSettingsDialog.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(dialog);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void assertSize(AppSettings settings, int width, int height) {
        assertEquals(width, settings.getWindowWidth());
        assertEquals(height, settings.getWindowHeight());
        assertEquals(width, settings.getWidth());
        assertEquals(height, settings.getHeight());
    }
}
