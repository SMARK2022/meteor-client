/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.render;

import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Zoom;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.joml.Vector4f;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class NametagUtils {
    private static final Vector4f vec4 = new Vector4f();
    private static final Vector4f mmMat4 = new Vector4f();
    private static final Vector4f pmMat4 = new Vector4f();
    private static final Vector3d camera = new Vector3d();
    private static final Vector3d cameraNegated = new Vector3d();
    private static final Matrix4f model = new Matrix4f();
    private static final Matrix4f projection = new Matrix4f();
    private static double windowScale;

    public static double scale;

    // 调试用：每秒只打印一次日志
    private static long lastLogTime = 0;
    private static final boolean DEBUG_ENABLED = false; // 已修复，禁用调试日志

    private NametagUtils() {
    }

    public static void onRender(Matrix4f modelView) {
        model.set(modelView);
        NametagUtils.projection.set(RenderUtils.projection);

        Utils.set(camera, mc.gameRenderer.getCamera().getPos());
        cameraNegated.set(camera);
        cameraNegated.negate();

        windowScale = mc.getWindow().calculateScaleFactor(1, false);
    }

    public static boolean to2D(Vector3d pos, double scale) {
        return to2D(pos, scale, true);
    }

    public static boolean to2D(Vector3d pos, double scale, boolean distanceScaling) {
        return to2D(pos, scale, distanceScaling, false);
    }

    public static boolean to2D(Vector3d pos, double scale, boolean distanceScaling, boolean allowBehind) {
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

        toScreen(pmMat4);
        double x = pmMat4.x * mc.getWindow().getFramebufferWidth();
        double y = pmMat4.y * mc.getWindow().getFramebufferHeight();

        if (behind) {
            x = mc.getWindow().getFramebufferWidth() - x;
            y = mc.getWindow().getFramebufferHeight() - y;
        }

        if (Double.isInfinite(x) || Double.isInfinite(y)) return false;

        // 保存原始值用于日志
        double originalX = x;
        double originalY = y;

        // 计算最终的屏幕坐标（转换为GUI缩放坐标系）
        double guiScale = mc.getWindow().getScaleFactor();
        double finalX = x / guiScale;
        double finalY = (mc.getWindow().getFramebufferHeight() - y) / guiScale;

        // 调试日志 - 每500ms打印一次
        if (DEBUG_ENABLED && System.currentTimeMillis() - lastLogTime > 500) {
            lastLogTime = System.currentTimeMillis();

            int fbWidth = mc.getWindow().getFramebufferWidth();
            int fbHeight = mc.getWindow().getFramebufferHeight();
            int winWidth = mc.getWindow().getWidth();
            int winHeight = mc.getWindow().getHeight();
            int scaledWidth = mc.getWindow().getScaledWidth();
            int scaledHeight = mc.getWindow().getScaledHeight();

            // 屏幕中心坐标（使用GUI缩放）
            double screenCenterX = scaledWidth / 2.0;
            double screenCenterY = scaledHeight / 2.0;

            MeteorClient.LOG.info("=== NametagUtils Debug ===");
            MeteorClient.LOG.info("Window: fbWidth={}, fbHeight={}, winWidth={}, winHeight={}", fbWidth, fbHeight, winWidth, winHeight);
            MeteorClient.LOG.info("Scale: guiScale={}, windowScale={}, scaledWidth={}, scaledHeight={}", guiScale, windowScale, scaledWidth, scaledHeight);
            MeteorClient.LOG.info("Screen Center: x={}, y={}", screenCenterX, screenCenterY);
            MeteorClient.LOG.info("pmMat4 after toScreen: x={}, y={}, z={}, w={}", pmMat4.x, pmMat4.y, pmMat4.z, pmMat4.w);
            MeteorClient.LOG.info("Raw screen coords: x={}, y={}", originalX, originalY);
            MeteorClient.LOG.info("Final coords: x={}, y={}", finalX, finalY);
            MeteorClient.LOG.info("World pos: x={}, y={}, z={}", pos.x, pos.y, pos.z);
            MeteorClient.LOG.info("Camera pos: x={}, y={}, z={}", camera.x, camera.y, camera.z);
            MeteorClient.LOG.info("Projection matrix valid: {}", !projection.equals(new Matrix4f()));
            MeteorClient.LOG.info("RenderUtils.projection valid: {}", !RenderUtils.projection.equals(new Matrix4f()));
            MeteorClient.LOG.info("==========================");
        }

        pos.set(finalX, finalY, allowBehind ? pmMat4.w : pmMat4.z);
        return true;
    }

    public static void begin(Vector3d pos) {
        Matrix4fStack matrices = RenderSystem.getModelViewStack();
        begin(matrices, pos);
    }

    public static void begin(Vector3d pos, DrawContext drawContext) {
        begin(pos);

        MatrixStack matrices = drawContext.getMatrices();
        matrices.push();
        matrices.scale((float) (1.0f / mc.getWindow().getScaleFactor()), (float) (1.0f / mc.getWindow().getScaleFactor()), 1);
        matrices.translate((float) pos.x, (float) pos.y, 0);
        matrices.scale((float) scale, (float) scale, 1);
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
        drawContext.getMatrices().pop();
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
