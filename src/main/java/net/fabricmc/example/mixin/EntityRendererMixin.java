package net.fabricmc.example.mixin;

import net.fabricmc.example.minimap.WaypointBeamRenderer;
import net.minecraft.src.EntityRenderer;
import net.minecraft.src.Tessellator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Unique
	private static boolean beamErrorLogged = false;

	/**
	 * Just before the hand is drawn inside renderWorld: the 3D projection and the camera are still set,
	 * and the world and entities are already in the depth buffer, so the beams are hidden behind terrain.
	 */
	@Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/src/EntityRenderer;renderHand(FI)V"))
	private void renderWaypointBeams(float partialTicks, long finishTimeNano, CallbackInfo ci) {
		try {
			WaypointBeamRenderer.renderAll(partialTicks);
		} catch (Throwable t) {
			if (!beamErrorLogged) {
				beamErrorLogged = true;
				System.err.println("[Minimap CE] Waypoint beams failed to draw (logged once):");
				t.printStackTrace();
			}
			// Never leave the shared tessellator half-open: that would crash whatever draws next.
			try {
				Tessellator.instance.draw();
			} catch (Throwable ignored) {
			}
		}
	}
}
