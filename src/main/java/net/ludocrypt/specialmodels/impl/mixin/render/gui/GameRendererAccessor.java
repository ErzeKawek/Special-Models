package net.ludocrypt.specialmodels.impl.mixin.render.gui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.render.GameRenderer;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

	@Accessor("renderHand")
	boolean isRenderHand();

}
