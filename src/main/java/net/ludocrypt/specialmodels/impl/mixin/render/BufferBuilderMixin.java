package net.ludocrypt.specialmodels.impl.mixin.render;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferVertexConsumer;
import com.mojang.blaze3d.vertex.FixedColorVertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.ludocrypt.specialmodels.impl.access.StateBufferBuilderAccess;
import net.ludocrypt.specialmodels.impl.render.SpecialVertexFormats;
import net.ludocrypt.specialmodels.impl.render.Vec4b;

@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin extends FixedColorVertexConsumer implements BufferVertexConsumer, StateBufferBuilderAccess {

	@Shadow
	private boolean textured;
	@Shadow
	private int elementOffset;
	@Shadow
	private VertexFormat format;
	@Unique
	private Supplier<Vec4b> state;

	@Inject(method = "setFormat", at = @At("TAIL"))
	private void specialModels$setFormat(VertexFormat format, CallbackInfo ci) {

		if (format == SpecialVertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL_STATE) {
			textured = true;
		}

	}

	@Inject(method = "Lcom/mojang/blaze3d/vertex/BufferBuilder;vertex(FFFFFFFFFIIFFF)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;putByte(IB)V", ordinal = 6, shift = Shift.AFTER), locals = LocalCapture.CAPTURE_FAILHARD)
	private void specialModels$vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ,
			CallbackInfo ci, int i) {

		if (format == SpecialVertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL_STATE) {
			Vec4b state = this.state.get();
			this.putByte(i + 7, state.getX());
			this.putByte(i + 8, state.getY());
			this.putByte(i + 9, state.getZ());
			this.putByte(i + 10, state.getW());
			elementOffset += 4;
		}

	}

	@Override
	public Supplier<Vec4b> stateAccessor() {
		return state;
	}

	@Override
	public void setStateAccessor(Supplier<Vec4b> stateAccessor) {
		this.state = stateAccessor;
	}

}
