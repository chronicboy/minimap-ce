package net.fabricmc.example.mixin;

import net.fabricmc.example.BTWNavigator;
import net.fabricmc.example.minimap.GuiFullscreenMap;
import net.fabricmc.example.minimap.WaypointBeamRenderer;
import net.fabricmc.example.minimap.engine.MapEngine;
import net.minecraft.src.GuiIngame;
import net.minecraft.src.Minecraft;
import net.minecraft.src.ScaledResolution;
import net.minecraft.src.Tessellator;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiIngame.class)
public class GuiIngameMixin {
	@Shadow
	@Final
	private Minecraft mc;

	@Unique
	private static int minimapFailures = 0;

	@Inject(at = @At("RETURN"), method = "renderGameOverlay")
	private void renderNavigatorHUD(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
		if (mc.thePlayer == null || mc.theWorld == null || minimapFailures >= 5) {
			return;
		}

		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			// Map upkeep runs every frame, whether or not the minimap itself is visible.
			MapEngine.INSTANCE.frame(mc);

			ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
			boolean fullscreenMap = mc.currentScreen instanceof GuiFullscreenMap;
			// Waypoint labels in the world, under the minimap.
			if (!fullscreenMap) {
				try {
					WaypointBeamRenderer.renderHud(mc, sr.getScaledWidth(), sr.getScaledHeight(), sr.getScaleFactor());
				} catch (Throwable t) {
					try {
						Tessellator.instance.draw();
					} catch (Throwable ignored) {
					}
				}
			}
			// Hidden behind the F3 overlay and the fullscreen map.
			if (mc.gameSettings.debugScreenState == 0 && !fullscreenMap && mc.theWorld.getWorldInfo() != null
					&& mc.theWorld.provider != null) {
				BTWNavigator.minimapRenderer.renderMinimap(mc, mc.theWorld, mc.thePlayer, sr.getScaledWidth(),
						sr.getScaledHeight(), sr.getScaleFactor(), partialTicks);
			}
			minimapFailures = 0;
		} catch (Throwable t) {
			minimapFailures++;
			System.err.println("[Minimap CE] Minimap rendering failed (" + minimapFailures + "/5)"
					+ (minimapFailures >= 5 ? ", disabling it for this session:" : ":"));
			t.printStackTrace();
			// Never leave the shared tessellator half-open: that would crash the next thing that draws.
			try {
				Tessellator.instance.draw();
			} catch (Throwable ignored) {
			}
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		}
	}
}
