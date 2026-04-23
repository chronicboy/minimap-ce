package net.fabricmc.example.mixin;

import net.minecraft.src.GameSettings;
import net.minecraft.src.KeyBinding;
import net.minecraft.src.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(GameSettings.class)
public abstract class GameSettingsMixin {
	@Shadow
	public KeyBinding[] keyBindings;

	@Inject(method = "<init>(Lnet/minecraft/src/Minecraft;Ljava/io/File;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/src/GameSettings;loadOptions()V"))
	private void addMinimapKeybind(Minecraft par1Minecraft, File par2File, CallbackInfo ci) {
		// Add to keyBindings array
		KeyBinding[] newKeyBindings = new KeyBinding[keyBindings.length + 6];
		System.arraycopy(keyBindings, 0, newKeyBindings, 0, keyBindings.length);
		newKeyBindings[keyBindings.length] = net.fabricmc.example.minimap.MapConfig.toggleEntitiesKey;
		newKeyBindings[keyBindings.length + 1] = net.fabricmc.example.minimap.MapConfig.increaseSizeKey;
		newKeyBindings[keyBindings.length + 2] = net.fabricmc.example.minimap.MapConfig.decreaseSizeKey;
		newKeyBindings[keyBindings.length + 3] = net.fabricmc.example.minimap.MapConfig.waypointGuiKey;
		newKeyBindings[keyBindings.length + 4] = net.fabricmc.example.minimap.MapConfig.fullscreenMapKey;
		newKeyBindings[keyBindings.length + 5] = net.fabricmc.example.minimap.MapConfig.settingsKey;
		keyBindings = newKeyBindings;

		// Reset key binding array
		KeyBinding.resetKeyBindingArrayAndHash();
	}
}
