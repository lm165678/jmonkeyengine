package com.jme3.renderer.opengl;

import com.jme3.renderer.Caps;
import com.jme3.shader.Shader;
import com.jme3.shader.Uniform;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Renderer-owned immutable texture snapshots. Original mutable textures never
 * acquire handles, so conventional draws and sampler edits remain legal.
 * CPU storage is borrowed; snapshot retirement must not free source pixel buffers.
 * GPU-only/render-target images retain ordinary texture-unit binding.
 */
final class BindlessTextureCache {
    private static final int MAX_TEXTURES = 256;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Pattern SAMPLER = Pattern.compile("\\buniform\\s+((?:(?:lowp|mediump|highp)\\s+)?[iu]?sampler\\w+\\s+)([A-Za-z_][A-Za-z_0-9]*)");
    private final GLRenderer renderer;
    private final Map<Texture, Entry> textures = new java.util.IdentityHashMap<>();
    private final Map<Shader, Shader> variants = new WeakHashMap<>();
    // Image.hashCode hashes pixel contents. Never use content-keyed maps on the draw path.
    private final java.util.List<java.lang.ref.WeakReference<Image>> renderTargets = new ArrayList<>();
    private long frame;
    private long bytes;

    private static final class Entry {
        Image source;
        long revision;
        Texture snapshot;
        long handle;
        long bytes;
        long used;
        int anisotropy;
    }

    BindlessTextureCache(GLRenderer renderer) {
        this.renderer = renderer;
    }

    Shader variant(Shader original) {
        if (!renderer.getCaps().contains(Caps.BindlessTextures)) {
            return original;
        }
        Shader cached = variants.get(original);
        if (cached != null) {
            return cached;
        }
        for (Shader.ShaderSource source : original.getSources()) {
            // Explicit bound-only declarations are an application contract.
            if (source.getSource().contains("bound_sampler")) {
                return original;
            }
        }
        Shader result = new Shader();
        for (Shader.ShaderSource source : original.getSources()) {
            java.util.regex.Matcher declarations = SAMPLER.matcher(source.getSource());
            while (declarations.find()) {
                String name = declarations.group(2);
                if (name.startsWith("m_") || name.startsWith("g_")) {
                    result.getUniform(name).setBindlessSampler(true);
                }
            }
            String code = SAMPLER.matcher(source.getSource()).replaceAll("layout(bindless_sampler) uniform $1$2");
            result.addSource(source.getType(), source.getName() + " [bindless]", code,
                    "#extension GL_ARB_bindless_texture : require\n" + source.getDefines(), source.getLanguage());
        }
        for (Uniform uniform : original.getBoundUniforms()) {
            result.addUniformBinding(uniform.getBinding());
        }
        variants.put(original, result);
        return result;
    }

    boolean supports(Uniform uniform) {
        // Macro-generated or explicitly unsupported declarations keep ordinary units.
        return uniform.isBindlessSampler();
    }

    void renderTarget(Image image) {
        if (!isRenderTarget(image)) {
            renderTargets.add(new java.lang.ref.WeakReference<>(image));
        }
    }

    private boolean isRenderTarget(Image image) {
        Iterator<java.lang.ref.WeakReference<Image>> iterator = renderTargets.iterator();
        while (iterator.hasNext()) {
            Image target = iterator.next().get();
            if (target == null) {
                iterator.remove();
            } else if (target == image) {
                return true;
            }
        }
        return false;
    }

    long acquire(Texture texture) {
        Image image = texture.getImage();
        if (image == null || image.getMultiSamples() > 1 || isRenderTarget(image)
                || image.getData() == null || image.getData().isEmpty()) {
            return 0;
        }
        long size = 0;
        for (ByteBuffer data : image.getData()) {
            if (data == null) {
                return 0;
            }
            size += data.capacity();
        }
        if (image.isGeneratedMipmapsRequired() && !image.hasMipmaps()) {
            size += size / 3;
        }
        Entry entry = textures.get(texture);
        if (entry != null && !matches(entry, texture)) {
            textures.remove(texture);
            retire(entry, false);
            entry = null;
        }
        if (entry == null) {
            if (textures.size() >= MAX_TEXTURES || size == 0 || size > MAX_BYTES - bytes) {
                return 0;
            }
            entry = new Entry();
            entry.source = image;
            entry.revision = image.getContentRevision();
            entry.snapshot = texture.clone();
            entry.snapshot.setImage(image.clone());
            entry.anisotropy = renderer.getDefaultAnisotropicFilter();
            entry.bytes = size;
            // Upload and configure once, before handle extraction makes storage/state immutable.
            entry.handle = renderer.getTextureHandle(entry.snapshot);
            renderer.setTextureHandleResident(entry.handle, true);
            bytes += size;
            textures.put(texture, entry);
        }
        entry.used = frame;
        return entry.handle;
    }

    private boolean matches(Entry entry, Texture texture) {
        Texture frozen = entry.snapshot;
        if (entry.source != texture.getImage() || entry.revision != texture.getImage().getContentRevision()
                || frozen.getType() != texture.getType() || frozen.getMinFilter() != texture.getMinFilter()
                || frozen.getMagFilter() != texture.getMagFilter()
                || frozen.getAnisotropicFilter() != texture.getAnisotropicFilter()
                || entry.anisotropy != renderer.getDefaultAnisotropicFilter()
                || frozen.getShadowCompareMode() != texture.getShadowCompareMode()
                || frozen.getImage().getColorSpace() != texture.getImage().getColorSpace()) {
            return false;
        }
        for (Texture.WrapAxis axis : Texture.WrapAxis.values()) {
            if (axis == Texture.WrapAxis.R && (texture.getType() == Texture.Type.TwoDimensional
                    || texture.getType() == Texture.Type.TwoDimensionalArray)) {
                continue;
            }
            if (frozen.getWrap(axis) != texture.getWrap(axis)) {
                return false;
            }
        }
        return true;
    }

    int count() {
        return textures.size();
    }

    long bytes() {
        return bytes;
    }

    void endFrame() {
        frame++;
        Iterator<Entry> iterator = textures.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (frame - entry.used > 120 || isRenderTarget(entry.source)) {
                iterator.remove();
                retire(entry, false);
            }
        }
    }

    private void retire(Entry entry, boolean contextLost) {
        bytes -= entry.bytes;
        if (!contextLost) {
            renderer.setTextureHandleResident(entry.handle, false);
            // Image.clone borrows CPU buffers. Dispose GPU storage only.
            entry.snapshot.getImage().setData(new ArrayList<ByteBuffer>());
            entry.snapshot.getImage().dispose();
        }
    }

    void close(boolean contextLost) {
        for (Entry entry : textures.values()) {
            retire(entry, contextLost);
        }
        textures.clear();
        variants.clear();
        renderTargets.clear();
        bytes = 0;
    }
}
