package net.ludocrypt.specialmodels.api;

import org.quiltmc.loader.api.minecraft.ClientOnly;

import com.mojang.blaze3d.systems.RenderSystem;

import net.ludocrypt.specialmodels.impl.render.MutableQuad;
import net.minecraft.client.render.ShaderProgram;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registry;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;

public class TexturedSpecialModelRenderer extends SpecialModelRenderer {

	public static final SpecialModelRenderer TEXTURED = Registry.register(SpecialModelRenderer.SPECIAL_MODEL_RENDERER, new Identifier("specialmodels", "textured"), new TexturedSpecialModelRenderer());

	@Override
	@ClientOnly
	public void setup(MatrixStack matrices, float tickDelta, ShaderProgram shader) {
		RenderSystem.setShaderTexture(0, PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
	}

	@Override
	@ClientOnly
	public MutableQuad modifyQuad(MutableQuad quad) {
		return quad;
	}

	public static void init() {

	}

}
