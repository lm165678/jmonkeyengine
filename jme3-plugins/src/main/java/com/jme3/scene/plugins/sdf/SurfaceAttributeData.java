package com.jme3.scene.plugins.sdf;

import com.jme3.export.*;
import com.jme3.texture.*;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * CPU-owned, j3o-serializable surface pixels for every card in a baked layout.
 * Linear float data preserves HDR emission, signed normals and normalized depth.
 * Rows run bottom-to-top as in OpenGL. Each card packs diffuse RGBA, normal RGBA,
 * emission RGBA, then one depth float per pixel. No scene lighting is included.
 *
 * <pre>{@code
 * // 第一步：加载 j3o，验证依赖仍匹配；只有 SDF 的文件可能没有 SurfaceBakeData。
 * SurfaceBakeData data = model.getUserData(SurfaceBakeData.USER_DATA_KEY);
 * if (data.validate(model, revision) != SurfaceBakeData.Status.VALID) {
 *     throw new IllegalStateException("需要重新烘焙");
 * }
 * SurfaceAttributeData pixels = data.getAttributes();
 * // 第二步：一键创建所有卡片的纹理；没有重新绘制模型，没有 GPU 读回。
 * SurfaceAttributeData.Textures textures = pixels.uploadAll();
 * // 第三步：按布局的 cardIndex 使用。首次被 renderer 使用时上传到 GPU。
 * material.setTexture("Attribute", textures.getTexture(4, SurfaceAttributeCache.Channel.DIFFUSE));
 * material.setTexture("Depth", textures.getDepthTexture(4));
 * material.setMatrix4("LocalToClip", data.getLayout().createCamera(4, pixels.getResolution()).getViewProjectionMatrix());
 * // 第四步：卸载时渲染线程关闭纹理；CPU pixels 仍随模型保存，可重新上传。
 * textures.close();
 * }</pre>
 * A stale bake must not be uploaded blindly. Lighting/instance placement changes
 * do not alter local attributes; geometry/material/dependency changes do.
 */
public final class SurfaceAttributeData implements Savable {
    private int resolution;
    private float[][] cards;

    /** Deserialization only. */
    public SurfaceAttributeData() {
    }

    SurfaceAttributeData(int resolution, float[][] cards) {
        this.resolution = resolution;
        this.cards = cards;
        validate();
    }

    /** @return capture edge length */
    public int getResolution() {
        return resolution;
    }

    /** @return number of complete captured cards, including empty cards */
    public int getCardCount() {
        return cards.length;
    }

    /** @return uncompressed CPU payload bytes, excluding arrays/object overhead */
    public long getByteSize() {
        return (long) resolution * resolution * cards.length * 13 * Float.BYTES;
    }

    /**
     * Returns a defensive copy for SDK export/debugging; not a per-frame API.
     * @param card layout card index
     * @return packed 13-float-per-pixel planes as documented on the class
     */
    public float[] copyCardData(int card) {
        return cards[card].clone();
    }

    // Package-private, read-only access for reference hit queries; no per-hit array copy.
    float component(int card, int offset) {
        return cards[card][offset];
    }

    /**
     * Creates all texture images from saved pixels, without drawing or readback.
     * Actual upload occurs when renderer first samples them. Caller owns result;
     * peak memory includes these independent direct-buffer copies plus CPU data.
     * Suitable for a single-asset preview. Multi-object scenes should use
     * SurfaceAttributeAtlas to bound residency rather than calling this per instance.
     * @return independently owned texture set
     */
    public Textures uploadAll() {
        return new Textures(this);
    }

    /** Owned texture set; close on renderer thread after removing consumers. */
    public static final class Textures implements AutoCloseable {
        private final Texture2D[][] textures;
        private boolean closed;

        private Textures(SurfaceAttributeData data) {
            textures = new Texture2D[data.cards.length][4];
            int pixels = data.resolution * data.resolution;
            try {
                for (int card = 0; card < textures.length; card++) {
                    for (int channel = 0; channel < 4; channel++) {
                        int count = pixels * (channel == 3 ? 1 : 4);
                        int offset = pixels * channel * 4;
                        ByteBuffer bytes = BufferUtils.createByteBuffer(count * Float.BYTES);
                        bytes.asFloatBuffer().put(data.cards[card], offset, count);
                        Image.Format format = channel == 3 ? Image.Format.R32F : Image.Format.RGBA32F;
                        Texture2D texture = new Texture2D(new Image(format, data.resolution,
                                data.resolution, bytes, ColorSpace.Linear));
                        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
                        texture.setMagFilter(Texture.MagFilter.Nearest);
                        texture.setWrap(Texture.WrapMode.EdgeClamp);
                        textures[card][channel] = texture;
                    }
                }
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        /** @param card card index @param channel attribute @return borrowed texture */
        public Texture2D getTexture(int card, SurfaceAttributeCache.Channel channel) {
            requireOpen();
            return textures[card][channel.ordinal()];
        }

        /**
         * Saved normalized depth, sampled as an ordinary R32F texture. It is not
         * a depth-attachment format; use it with projectSurfaceCard(), not as a framebuffer depth target.
         * @param card card index
         * @return borrowed depth-value texture
         */
        public Texture2D getDepthTexture(int card) {
            requireOpen();
            return textures[card][3];
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Baked surface textures closed");
            }
        }

        @Override
        public void close() {
            if (!closed) {
                for (Texture2D[] card : textures) {
                    for (Texture2D texture : card) {
                        if (texture != null) {
                            texture.getImage().dispose();
                        }
                    }
                }
                closed = true;
            }
        }
    }

    private void validate() {
        if (resolution < 8 || resolution > 2048 || cards == null || cards.length == 0) {
            throw new IllegalArgumentException("Invalid baked surface metadata");
        }
        int pixels = resolution * resolution;
        for (float[] card : cards) {
            if (card == null || card.length != pixels * 13) {
                throw new IllegalArgumentException("Invalid baked surface card size");
            }
            for (int i = 0; i < card.length; i++) {
                if (!Float.isFinite(card[i]) || (i >= pixels * 12 && (card[i] < 0 || card[i] > 1))) {
                    throw new IllegalArgumentException("Invalid baked surface sample");
                }
            }
        }
    }

    @Override
    public void write(JmeExporter exporter) throws IOException {
        OutputCapsule c = exporter.getCapsule(this);
        c.write(1, "version", 0);
        c.write(resolution, "resolution", 0);
        c.write(cards, "cards", null);
    }

    @Override
    public void read(JmeImporter importer) throws IOException {
        InputCapsule c = importer.getCapsule(this);
        if (c.readInt("version", 0) != 1) {
            throw new IOException("Unsupported surface pixel version");
        }
        resolution = c.readInt("resolution", 0);
        cards = c.readFloatArray2D("cards", null);
        try {
            validate();
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid baked surface data", exception);
        }
    }
}
