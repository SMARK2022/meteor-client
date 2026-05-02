/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.events.render;

import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

public class Render3DEvent {
    private static final Render3DEvent INSTANCE = new Render3DEvent();
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f frustumMatrix = new Matrix4f();

    public MatrixStack matrices;
    public Renderer3D renderer;
    public Renderer3D depthRenderer;
    public double frameTime;
    public float tickDelta;
    public double offsetX, offsetY, offsetZ;

    public static Render3DEvent get(MatrixStack matrices, Renderer3D renderer, Renderer3D depthRenderer, Matrix4f projection, Matrix4f view, float tickDelta, double offsetX, double offsetY, double offsetZ) {
        INSTANCE.matrices = matrices;
        INSTANCE.renderer = renderer;
        INSTANCE.depthRenderer = depthRenderer;
        INSTANCE.frameTime = Utils.frameTime;
        INSTANCE.tickDelta = tickDelta;
        INSTANCE.offsetX = offsetX;
        INSTANCE.offsetY = offsetY;
        INSTANCE.offsetZ = offsetZ;
        INSTANCE.frustum.set(INSTANCE.frustumMatrix.set(projection).mul(view));
        return INSTANCE;
    }

    public boolean isVisible(Box box) {
        return isVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public boolean isVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        return frustum.testAab(
            (float) (x1 - offsetX), (float) (y1 - offsetY), (float) (z1 - offsetZ),
            (float) (x2 - offsetX), (float) (y2 - offsetY), (float) (z2 - offsetZ)
        );
    }
}
