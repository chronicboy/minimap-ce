package net.fabricmc.example.mixin;

import net.fabricmc.example.minimap.WaypointBeamRenderer;
import net.minecraft.src.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    /**
     * Inject just BEFORE renderHand is called inside renderWorld.
     * At this point:
     * - The 3D perspective projection is still active
     * - The camera transform (modelview) is in place
     * - All world geometry + entities have been rendered
     * - The depth buffer contains the full scene
     * This is the ideal place for custom 3D overlays.
     */
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/src/EntityRenderer;renderHand(FI)V"))
    private void renderWaypointBeams(float partialTicks, long finishTimeNano, CallbackInfo ci) {
        try {
            WaypointBeamRenderer.renderAll(partialTicks);
        } catch (Exception e) {
            // Silently ignore rendering errors to avoid crashing
        }
    }
}
