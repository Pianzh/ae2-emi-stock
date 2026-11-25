package local.stock.mixin;

import local.stock.client.StockOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, priority = 1450, remap = false)
abstract class RemoteOrderMouseMixin {
    @Unique private boolean ae2emistock$captured;

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void ae2emistock$order(long handle, MouseButtonInfo button, int action, CallbackInfo callback) {
        if (button.button() != InputConstants.MOUSE_BUTTON_MIDDLE) return;
        var client = Minecraft.getInstance();
        var window = client.getWindow();
        if (window.handle() != handle) return;
        if (action == InputConstants.PRESS) {
            var mouse = client.mouseHandler;
            ae2emistock$captured = StockOverlay.tryOrder(client, (int)mouse.getScaledXPos(window), (int)mouse.getScaledYPos(window));
            if (ae2emistock$captured) callback.cancel();
        } else if (action == InputConstants.RELEASE && ae2emistock$captured) {
            ae2emistock$captured = false;
            callback.cancel();
        }
    }
}
