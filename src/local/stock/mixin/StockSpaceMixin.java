package local.stock.mixin;

import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import local.stock.client.StockOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ScreenSpace.class, remap = false)
abstract class StockSpaceMixin {
    @Inject(method = "render", at = @At("RETURN"))
    private void ae2emistock$draw(EmiDrawContext draw, int mouseX, int mouseY, float delta, int offset, CallbackInfo ci) {
        StockOverlay.draw((ScreenSpace)(Object)this, draw.raw(), offset);
    }
}
