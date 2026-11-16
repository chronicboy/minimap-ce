package net.fabricmc.example.mixin;

import net.fabricmc.example.minimap.MapConfig;
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
		// Append our bindings before options.txt is read, so saved keys are restored.
		KeyBinding[] extra = MapConfig.ALL_KEYS;
		KeyBinding[] newKeyBindings = new KeyBinding[keyBindings.length + extra.length];
		System.arraycopy(keyBindings, 0, newKeyBindings, 0, keyBindings.length);
		System.arraycopy(extra, 0, newKeyBindings, keyBindings.length, extra.length);
		keyBindings = newKeyBindings;

		KeyBinding.resetKeyBindingArrayAndHash();
	}
}
