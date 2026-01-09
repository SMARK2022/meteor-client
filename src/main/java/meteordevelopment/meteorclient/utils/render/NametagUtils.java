/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Zoom;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import org.joml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class NametagUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(NametagUtils.class);
    private static final Vector4f vec4 = new Vector4f();
    private static final Vector4f mmMat4 = new Vector4f();
    private static final Vector4f pmMat4 = new Vector4f();
    private static final Vector3d camera = new Vector3d();
    private static final Vector3d cameraNegated = new Vector3d();
    private static final Matrix4f model = new Matrix4f();
    private static final Matrix4f projection = new Matrix4f();

    public static double scale;

    private NametagUtils() {
    }

    public static void onRender(Matrix4f modelView) {
        model.set(modelView);
        NametagUtils.projection.set(RenderUtils.projection);

        Utils.set(camera, mc.gameRenderer.getCamera().getCameraPos());
        cameraNegated.set(camera);
        cameraNegated.negate();

        // Log window scale information
        double calcScaleFactor1 = mc.getWindow().calculateScaleFactor(1, false);
        double calcScaleFactor0 = mc.getWindow().calculateScaleFactor(0, false);
        float guiScale = mc.getWindow().getScaleFactor();
        LOGGER.info("[NametagUtils] onRender - calculateScaleFactor(1,false)={}, calculateScaleFactor(0,false)={}, getScaleFactor()={}", 
            calcScaleFactor1, calcScaleFactor0, guiScale);
        LOGGER.info("[NametagUtils] onRender - FramebufferWidth={}, FramebufferHeight={}, ScaledWidth={}, ScaledHeight={}", 
            mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight(),
            mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
    }

    public static boolean to2D(Vector3d pos, double scale) {
        return to2D(pos, scale, true);
    }

    public static boolean to2D(Vector3d pos, double scale, boolean distanceScaling) {
        return to2D(pos, scale, distanceScaling, false);
    }

    public static boolean to2D(Vector3d pos, double scale, boolean distanceScaling, boolean allowBehind) {
        // Save original position for logging
        double origPosX = pos.x;
        double origPosY = pos.y;
        double origPosZ = pos.z;

        Zoom zoom = Modules.get().get(Zoom.class);
        NametagUtils.scale = scale * zoom.getScaling();
        if (distanceScaling) {
            NametagUtils.scale *= getScale(pos);
        }

        vec4.set(cameraNegated.x + pos.x, cameraNegated.y + pos.y, cameraNegated.z + pos.z, 1);

        vec4.mul(model, mmMat4);
        mmMat4.mul(projection, pmMat4);

        boolean behind = pmMat4.w <= 0.f;

        if (behind && !allowBehind) return false;

        // Log before toScreen transformation
        LOGGER.info("[NametagUtils] to2D BEFORE toScreen - origPos=({}, {}, {}), pmMat4=({}, {}, {}, {}), behind={}", 
            origPosX, origPosY, origPosZ, pmMat4.x, pmMat4.y, pmMat4.z, pmMat4.w, behind);

        toScreen(pmMat4);

        // Log after toScreen transformation
        LOGGER.info("[NametagUtils] to2D AFTER toScreen - pmMat4=({}, {}, {}, {})", 
            pmMat4.x, pmMat4.y, pmMat4.z, pmMat4.w);

        double x = pmMat4.x * mc.getWindow().getFramebufferWidth();
        double y = pmMat4.y * mc.getWindow().getFramebufferHeight();

        LOGGER.info("[NametagUtils] to2D AFTER multiply - x={}, y={} (FramebufferWidth={}, FramebufferHeight={})", 
            x, y, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());

        if (behind) {
            x = mc.getWindow().getFramebufferWidth() - x;
            y = mc.getWindow().getFramebufferHeight() - y;
            LOGGER.info("[NametagUtils] to2D AFTER behind adjustment - x={}, y={}", x, y);
        }

        if (Double.isInfinite(x) || Double.isInfinite(y)) return false;

        double finalX = x;
        double finalY = mc.getWindow().getFramebufferHeight() - y;
        double finalZ = allowBehind ? pmMat4.w : pmMat4.z;

        LOGGER.info("[NametagUtils] to2D FINAL - pos.set({}, {}, {})", finalX, finalY, finalZ);

        pos.set(finalX, finalY, finalZ);
        return true;
    }

    public static void begin(Vector3d pos) {
        Matrix4fStack matrices = RenderSystem.getModelViewStack();
        begin(matrices, pos);
    }

    public static void begin(Vector3d pos, DrawContext drawContext) {
        begin(pos);

        Matrix3x2fStack matrices = drawContext.getMatrices();
        matrices.pushMatrix();
        float guiScaleFactor = mc.getWindow().getScaleFactor();
        matrices.scale(1.0f / guiScaleFactor);
        matrices.translate((float) pos.x, (float) pos.y);
        matrices.scale((float) scale, (float) scale);

        LOGGER.info("[NametagUtils] begin(DrawContext) - pos=({}, {}), getScaleFactor()={}, scale by={}, final scale={}", 
            pos.x, pos.y, guiScaleFactor, 1.0f / guiScaleFactor, scale);
    }

    private static void begin(Matrix4fStack matrices, Vector3d pos) {
        matrices.pushMatrix();
        matrices.translate((float) pos.x, (float) pos.y, 0);
        matrices.scale((float) scale, (float) scale, 1);
    }

    public static void end() {
        RenderSystem.getModelViewStack().popMatrix();
    }

    public static void end(DrawContext drawContext) {
        end();
        drawContext.getMatrices().popMatrix();
    }

    private static double getScale(Vector3d pos) {
        double dist = camera.distance(pos);
        return MathHelper.clamp(1 - dist * 0.01, 0.5, Integer.MAX_VALUE);
    }

    private static void toScreen(Vector4f vec) {
        float newW = 1.0f / vec.w * 0.5f;

        vec.x = vec.x * newW + 0.5f;
        vec.y = vec.y * newW + 0.5f;
        vec.z = vec.z * newW + 0.5f;
        vec.w = newW;
    }
}
