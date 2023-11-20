package net.ludocrypt.specialmodels.api;

import org.joml.Matrix4f;
import org.quiltmc.loader.api.minecraft.ClientOnly;

import com.mojang.serialization.Lifecycle;

import net.ludocrypt.specialmodels.impl.mixin.registry.RegistriesAccessor;
import net.ludocrypt.specialmodels.impl.render.MutableQuad;
import net.ludocrypt.specialmodels.impl.render.Vec4b;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.ShaderProgram;
import net.minecraft.client.render.chunk.ChunkRenderRegion;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public abstract class SpecialModelRenderer {

	public static final RegistryKey<Registry<SpecialModelRenderer>> SPECIAL_MODEL_RENDERER_KEY = RegistryKey.ofRegistry(new Identifier("limlib/special_model_renderer"));
	public static final Registry<SpecialModelRenderer> SPECIAL_MODEL_RENDERER = RegistriesAccessor.callRegisterSimple(SPECIAL_MODEL_RENDERER_KEY, Lifecycle.stable(),
			registry -> TexturedSpecialModelRenderer.TEXTURED);

	@ClientOnly
	public abstract void setup(MatrixStack matrices, Matrix4f viewMatrix, Matrix4f positionMatrix, float tickDelta, ShaderProgram shader, BlockPos chunkOrigin);

	@ClientOnly
	public MutableQuad modifyQuad(MutableQuad quad) {
		return quad;
	}

	@ClientOnly
	public Matrix4f positionMatrix(Matrix4f in) {
		return in;
	}

	@ClientOnly
	public Matrix4f viewMatrix(Matrix4f in) {
		return in;
	}

	@ClientOnly
	public Vec4b appendState(ChunkRenderRegion chunkRenderRegion, BlockPos pos, BlockState state, long modelSeed) {
		return new Vec4b(0, 0, 0, 0);
	}

}
