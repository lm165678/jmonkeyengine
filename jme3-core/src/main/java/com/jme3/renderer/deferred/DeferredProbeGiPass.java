package com.jme3.renderer.deferred;

import com.jme3.material.RenderState;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector2f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.RendererException;
import com.jme3.renderer.TextureUnitException;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.shader.Shader;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture;
import static com.jme3.renderer.deferred.DeferredRenderContext.uniform;

/** Current-frame incident GI evaluation/reconstruction; owns no frame targets. */
final class DeferredProbeGiPass {
    private final RenderState state = new RenderState();
    private final Matrix4f inverse = new Matrix4f();
    private final Vector2f resolution = new Vector2f();

    DeferredProbeGiPass() {
        state.setDepthTest(false);
        state.setDepthWrite(false);
        state.setFaceCullMode(RenderState.FaceCullMode.Off);
    }

    void execute(DeferredRenderContext frame, RenderTarget output, RenderTarget half) {
        execute(frame, output, half, half == null ? 0 : 1);
    }

    void fallback(DeferredRenderContext frame, RenderTarget output, RenderTarget reconstructed) {
        execute(frame, output, reconstructed, 2);
    }

    private void execute(DeferredRenderContext frame, RenderTarget output, RenderTarget source, int phase) {
        Renderer renderer = frame.renderer;
        Camera camera = frame.camera();
        // Separate variants keep the default quality path free of the wider gather.
        int variant = phase == 1 && !frame.viewport.isProbeGiFullFallback() ? 3 : phase;
        Shader shader = frame.resources.probeGiShader(variant);
        renderer.setFrameBuffer(output.getFrameBuffer());
        try {
            frame.manager.setCamera(camera, false);
            renderer.setViewPort(0, 0, output.getFrameBuffer().getWidth(), output.getFrameBuffer().getHeight());
            String[] names = {"gbuffer0", "gbuffer1", "gbuffer2", "gbuffer3", "gbuffer4", "gbufferDepth"};
            for (int i = 0; i < names.length; i++) {
                texture(renderer, shader, names[i], i, frame.resources.targets[i]);
            }
            inverse.set(camera.getViewProjectionMatrix()).invertLocal();
            uniform(shader, "InverseViewProjection", VarType.Matrix4, inverse);
            uniform(shader, "Resolution", VarType.Vector2, resolution.set(camera.getWidth(), camera.getHeight()));
            frame.batch.probeGi.bind(renderer, shader, frame.resources.giReceivers, 6);
            if (phase == 1) {
                uniform(shader, "GiFullFallback", VarType.Boolean, frame.viewport.isProbeGiFullFallback());
                texture(renderer, shader, "GiHalfIrradiance", 11, source.getColorTexture(0));
                texture(renderer, shader, "GiHalfSky", 12, source.getColorTexture(1));
                texture(renderer, shader, "GiHalfGuide", 13, source.getColorTexture(2));
            } else if (phase == 2) {
                texture(renderer, shader, "GiReconstructionMask", 11, source.getColorTexture(2));
            }
            renderer.applyRenderState(state);
            if (phase == 2) {
                renderer.setBackgroundColor(com.jme3.math.ColorRGBA.BlackNoAlpha);
                renderer.clearBuffers(true, false, false);
            }
            renderer.setShader(shader);
            renderer.renderMesh(frame.resources.quad, 0, 1, null);
        } finally {
            renderer.setFrameBuffer(frame.viewport.getOutputFrameBuffer());
            renderer.setBackgroundColor(frame.viewport.getBackgroundColor());
            frame.manager.setCamera(camera, false);
        }
    }

    static void texture(Renderer renderer, Shader shader, String name, int unit, Texture texture) {
        try {
            renderer.setTexture(unit, texture);
        } catch (TextureUnitException failure) {
            throw new RendererException(failure);
        }
        uniform(shader, name, VarType.Int, unit);
    }
}
