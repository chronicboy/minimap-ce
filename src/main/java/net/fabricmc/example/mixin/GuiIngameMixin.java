package net.fabricmc.example.mixin;

import net.fabricmc.example.BTWNavigator;
import net.minecraft.src.*;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiIngame.class)
public class GuiIngameMixin {
	@Shadow
	@Final
	private Minecraft mc;

	@Inject(at = @At("RETURN"), method = "renderGameOverlay")
	private void renderNavigatorHUD(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
		if (mc.thePlayer == null || mc.theWorld == null) {
			return;
		}

		// Hide minimap when F3 debug overlay is open
		if (mc.gameSettings.debugScreenState != 0) {
			return;
		}

		ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
		try {
			if (mc.theWorld.getWorldInfo() != null && mc.theWorld.provider != null) {
				BTWNavigator.minimapRenderer.renderMinimap(mc.theWorld, mc.thePlayer, sr.getScaledWidth(),
						sr.getScaledHeight(), partialTicks);
			}
		} catch (Exception e) {
		}
	}
}
