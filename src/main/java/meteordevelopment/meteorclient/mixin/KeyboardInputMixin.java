/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Sneak;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void isPressed(CallbackInfo ci) {
        if (Modules.get().get(Sneak.class).doVanilla()) playerInput = new PlayerInput(
            playerInput.forward(),
            playerInput.backward(),
            playerInput.left(),
            playerInput.right(),
            playerInput.jump(),
            true,
            playerInput.sprint()
        );

        // MovementFix: 输入重映射 — 在 input.tick() 产生新鲜输入后、movement 物理使用输入前
        // 将 WASD 重映射到 target yaw 下仍保持原始视觉方向的 8 向合法按键
        if (Rotations.needsMoveFix()) {
            Rotations.applyMoveFix(this);
        }
    }
}
