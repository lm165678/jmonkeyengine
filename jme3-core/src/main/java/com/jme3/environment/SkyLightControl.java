package com.jme3.environment;

import com.jme3.asset.AssetManager;
import com.jme3.environment.baker.IBLGLEnvBakerLight;
import com.jme3.export.*;
import com.jme3.light.SkyLight;
import com.jme3.math.Vector3f;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.scene.control.AbstractControl;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.clone.Cloner;
import java.io.IOException;

/**
 * Sky-only HDR environment capture owned by a sky spatial. SkyFactory installs
 * this control automatically. The parent node is the light scope, so attach a
 * sky to the scene root for global coverage. Capture has no meaningful position.
 * Defaults: 128-pixel faces, quality SH, one capture on first render, savable HDR
 * results. Texture replacement, sky rotation and NormalScale changes trigger
 * recapture. In-place image edits require requestRebake(); existing GI volumes
 * need separate rebaking. LDR sRGB skies are decoded to linear radiance; their
 * missing HDR intensity range cannot be recovered. Disable the control for a background-only sky; re-enable to restore.
 * Attach/detach, enable changes and rebake requests belong on the render thread,
 * before geometric updates. GPU capture occurs in controlRender and restores the
 * previous framebuffer/camera. It requires floating-point render targets.
 * <pre>{@code
 * // 第一步：SkyFactory 自动创建 control；手工天空也可添加。
 * SkyLightControl lighting = new SkyLightControl(assetManager);
 * sky.addControl(lighting);
 * // 第二步：挂在场景根节点下，全局天光只捕捉这个天空，不捕捉室内物体。
 * rootNode.attachChild(sky);
 * // 第三步：修改天空纹理后重新捕捉；普通帧不会反复烘焙。
 * lighting.requestRebake();
 * // 第四步：背景模式不参与光照，取消禁用后恢复。
 * lighting.setEnabled(false);
 * }</pre>
 */
public final class SkyLightControl extends AbstractControl {
    private AssetManager assets;
    private SkyLight light = new SkyLight();
    private transient Node registered;
    private boolean dirty = true;
    private transient boolean capturing;
    private int resolution = 128;
    private transient Object previousTexture;
    private transient com.jme3.math.Quaternion previousRotation;
    private transient Vector3f previousNormalScale;

    public SkyLightControl() {
    }

    public SkyLightControl(AssetManager assets) {
        this.assets = java.util.Objects.requireNonNull(assets);
    }

    public SkyLight getLight() {
        return light;
    }

    public void requestRebake() {
        dirty = true;
        light.invalidateCaptureRevision();
    }

    public int getResolution() {
        return resolution;
    }

    /** Power-of-two capture size, 16..1024; changing schedules a new capture. */
    public void setResolution(int value) {
        if (value < 16 || value > 1024 || (value & (value - 1)) != 0) {
            throw new IllegalArgumentException("Power-of-two size 16..1024 required");
        }
        resolution = value;
        requestRebake();
    }

    @Override
    public void setSpatial(Spatial spatial) {
        if (registered != null) {
            registered.removeLight(light);
            registered = null;
        }
        super.setSpatial(spatial);
        synchronizeParent();
    }

    /** Internal parent-change hook; prevents orphan lighting after sky removal. */
    public void synchronizeParent() {
        Node parent = spatial != null && enabled ? spatial.getParent() : null;
        if (registered != parent) {
            if (registered != null) {
                registered.removeLight(light);
            }
            registered = parent;
            boolean present = false;
            if (parent != null) {
                for (com.jme3.light.Light existing : parent.getLocalLightList()) {
                    present |= existing == light;
                }
            }
            if (parent != null && !present) {
                parent.addLight(light);
            }
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        light.setEnabled(enabled);
        synchronizeParent();
    }

    @Override
    protected void controlUpdate(float tpf) {
        synchronizeParent();
        if (spatial instanceof Geometry) {
            com.jme3.material.Material material = ((Geometry) spatial).getMaterial();
            com.jme3.material.MatParam texture = material.getParam("Texture");
            com.jme3.material.MatParam normalScale = material.getParam("NormalScale");
            Object currentTexture = texture == null ? null : texture.getValue();
            Vector3f scale = normalScale == null ? Vector3f.UNIT_XYZ : (Vector3f) normalScale.getValue();
            com.jme3.math.Quaternion rotation = spatial.getWorldRotation();
            if (previousRotation != null && (currentTexture != previousTexture
                    || !rotation.equals(previousRotation) || !scale.equals(previousNormalScale))) {
                requestRebake();
            }
            previousTexture = currentTexture;
            previousRotation = rotation.clone();
            previousNormalScale = scale.clone();
        }
    }

    @Override
    protected void controlRender(RenderManager manager, ViewPort view) {
        if (!dirty || capturing || assets == null) {
            return;
        }
        capturing = true;
        Camera oldCamera = manager.getCurrentCamera();
        int oldPlaneState = oldCamera == null ? 0 : oldCamera.getPlaneState();
        FrameBuffer oldBuffer = manager.getRenderer().getCurrentFrameBuffer();
        IBLGLEnvBakerLight baker = new IBLGLEnvBakerLight(manager, assets,
                Image.Format.RGBA16F, Image.Format.Depth, resolution, resolution);
        java.util.List<Image> captureImages = new java.util.ArrayList<>();
        try {
            Spatial sky = spatial.clone(true);
            sky.setLocalRotation(spatial.getWorldRotation());
            sky.depthFirstTraversal(child -> {
                if (child instanceof Geometry) {
                    com.jme3.material.Material material = ((Geometry) child).getMaterial();
                    com.jme3.material.MatParamTexture source = material.getTextureParam("Texture");
                    if (source != null && source.getTextureValue().getImage().getColorSpace()
                            == com.jme3.texture.image.ColorSpace.sRGB
                            && material.getMaterialDef().getMaterialParam("CaptureDecodeSrgb") != null) {
                        com.jme3.texture.Texture texture = source.getTextureValue().clone();
                        Image image = texture.getImage().clone();
                        image.setColorSpace(com.jme3.texture.image.ColorSpace.Linear);
                        texture.setImage(image);
                        material.setTexture("Texture", texture);
                        material.setBoolean("CaptureDecodeSrgb", true);
                        captureImages.add(image);
                    }
                }
                for (int i = child.getNumControls() - 1; i >= 0; i--) {
                    child.removeControl(child.getControl(i));
                }
            });
            sky.setLocalTranslation(Vector3f.ZERO);
            sky.updateGeometricState();
            baker.setTexturePulling(true);
            baker.setSphericalHarmonicsMode(IBLGLEnvBakerLight.SphericalHarmonicsMode.QUALITY);
            baker.bakeEnvironment(sky, Vector3f.ZERO, 0.1f, 1000f, null);
            baker.bakeSpecularIBL();
            baker.bakeSphericalHarmonicsCoefficients();
            light.setPrefilteredMap(baker.getSpecularIBL());
            int[] mips = light.getPrefilteredEnvMap().getImage().getMipMapSizes();
            light.setNbMipMaps(mips == null ? 1 : mips.length);
            light.setShCoeffs(baker.getSphericalHarmonicsCoefficients());
            light.setReady(true);
            dirty = false;
        } finally {
            baker.clean();
            for (Image image : captureImages) {
                image.dispose();
            }
            manager.getRenderer().setFrameBuffer(oldBuffer);
            if (oldCamera != null) {
                oldCamera.setPlaneState(oldPlaneState);
                manager.setCamera(oldCamera, false);
            }
            capturing = false;
        }
    }

    @Override
    public void cloneFields(Cloner cloner, Object original) {
        super.cloneFields(cloner, original);
        light = cloner.clone(light);
        registered = null;
        capturing = false;
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        super.write(exporter);
        OutputCapsule out = exporter.getCapsule(this);
        out.write(light, "skyLight", null);
        out.write(resolution, "resolution", 128);
        out.write(dirty, "dirty", true);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        super.read(importer);
        assets = importer.getAssetManager();
        InputCapsule in = importer.getCapsule(this);
        light = (SkyLight) in.readSavable("skyLight", new SkyLight());
        resolution = in.readInt("resolution", 128);
        dirty = in.readBoolean("dirty", true) || !light.isReady();
    }
}
