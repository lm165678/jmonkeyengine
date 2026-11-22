package jme3test.post;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.Platform;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.PaintMode;
import com.jme3.system.lwjgl.WindowsHdrAwtPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Shows near-black and above-white patches in an embedded HDR canvas. Requires
 * Windows HDR, SDL desktop OpenGL, float color buffers and WGL_NV_DX_interop2.
 * Use the HDR preview check box to switch presentation while keeping the canvas.
 * Run {@code gradlew :jme3-examples:awtHdrPreviewSmoke} on an HDR display to check
 * HDR activation, SDR restoration and HDR reactivation.
 *
 * <pre>{@code
 * // 第一步：在 EDT 创建 AWT 窗口与 WindowsHdrAwtPanel。
 * // 第二步：在渲染线程挂载视口，并要求使用线性合成。
 * panel.attachTo(true, viewPort, guiViewPort);
 * panel.setShaderSrgbOutput(assetManager);
 * // 第三步：开启 HDR 共享纹理呈现；关闭则回到 AWT SDR。
 * panel.setHdrPreviewEnabled(true);
 * }</pre>
 */
public final class TestAwtHdrPreview extends SimpleApplication {
    private final boolean verify;
    private WindowsHdrAwtPanel panel;
    private JFrame frame;
    private int frames;

    public static final class PreviewContext extends AwtPanelsContext {
        final WindowsHdrAwtPanel panel = (WindowsHdrAwtPanel) createPanel(PaintMode.Repaint, true);

        public PreviewContext() {
            setInputSource(panel);
        }

        @Override
        protected AwtPanel newPanel(PaintMode mode, boolean srgb, int bitsPerPixel) {
            return new WindowsHdrAwtPanel(mode, srgb, bitsPerPixel);
        }
    }

    private TestAwtHdrPreview(boolean verify) {
        this.verify = verify;
    }

    public static void main(String[] args) {
        if (JmeSystem.getPlatform().getOs() != Platform.Os.Windows) {
            throw new UnsupportedOperationException("This example requires Windows HDR");
        }
        SwingUtilities.invokeLater(() -> {
            TestAwtHdrPreview app = new TestAwtHdrPreview(args.length > 0 && "--verify".equals(args[0]));
            AppSettings settings = new AppSettings(true);
            settings.setCustomRenderer(PreviewContext.class);
            settings.setResolution(640, 320);
            settings.setGammaCorrection(true);
            settings.setAudioRenderer(null);
            app.setSettings(settings);
            app.setShowSettings(false);
            app.setPauseOnLostFocus(false);
            app.start();
        });
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayFps(false);
        setDisplayStatView(false);
        panel = ((PreviewContext) context).panel;
        panel.attachTo(true, viewPort, guiViewPort);
        panel.setShaderSrgbOutput(assetManager);
        panel.setHdrPreviewEnabled(true);
        for (int i = 0; i < 16; i++) {
            addPatch(i, 36, i * .001f);
            addPatch(i, 176, i * .5f);
        }
        SwingUtilities.invokeLater(this::showWindow);
    }

    private void addPatch(int column, int y, float value) {
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(value, value, value, 1f));
        Geometry patch = new Geometry("Gray " + value, new Quad(40, 108));
        patch.setMaterial(material);
        patch.setLocalTranslation(column * 40, y, 0);
        guiNode.attachChild(patch);
    }

    private void showWindow() {
        frame = new JFrame("AWT HDR preview");
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stop();
            }
        });
        JCheckBox hdr = new JCheckBox("HDR preview", true);
        hdr.addActionListener(event -> {
            boolean enabled = hdr.isSelected();
            enqueue(() -> panel.setHdrPreviewEnabled(enabled));
        });
        panel.setPreferredSize(new Dimension(640, 320));
        frame.add(panel, BorderLayout.CENTER);
        frame.add(hdr, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (!verify || !panel.isShowing()) {
            return;
        }
        switch (++frames) {
            case 20:
                requireHdr(true);
                panel.setHdrPreviewEnabled(false);
                break;
            case 40:
                requireHdr(false);
                panel.setHdrPreviewEnabled(true);
                break;
            case 60:
                requireHdr(true);
                System.out.println("AWT_HDR_PREVIEW_SWITCH_OK");
                stop();
                break;
            default:
                break;
        }
    }

    private void requireHdr(boolean expected) {
        if (panel.isHdrPreviewActive() != expected) {
            throw new AssertionError("Expected AWT HDR preview active=" + expected);
        }
    }

    @Override
    public void destroy() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
        super.destroy();
    }
}
