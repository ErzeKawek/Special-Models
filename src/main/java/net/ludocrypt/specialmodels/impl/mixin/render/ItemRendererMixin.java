package net.ludocrypt.specialmodels.impl.mixin.render;

import java.util.List;
import java.util.Set;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferRenderer;
import com.mojang.blaze3d.vertex.Tessellator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import com.mojang.blaze3d.vertex.VertexFormats;
import com.mojang.datafixers.util.Pair;

import net.ludocrypt.specialmodels.api.SpecialModelRenderer;
import net.ludocrypt.specialmodels.impl.SpecialModels;
import net.ludocrypt.specialmodels.impl.access.BakedModelAccess;
import net.ludocrypt.specialmodels.impl.access.ItemRendererAccess;
import net.ludocrypt.specialmodels.impl.access.WorldRendererAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.ShaderProgram;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Axis;
import net.minecraft.util.math.Direction;
import net.minecraft.util.random.RandomGenerator;

@Mixin(ItemRenderer.class)
public class ItemRendererMixin implements ItemRendererAccess {

	@Shadow
	@Final
	private ItemColors colors;

	@Unique
	private boolean inGui = false;

	@Inject(method = "Lnet/minecraft/client/render/item/ItemRenderer;renderBakedItemModel(Lnet/minecraft/client/render/model/BakedModel;Lnet/minecraft/item/ItemStack;IILnet/minecraft/client/util/math/MatrixStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V", at = @At("HEAD"))
	private void specialModels$renderBakedItemModel(BakedModel model, ItemStack stack, int light, int overlay, MatrixStack matrices, VertexConsumer vertices, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();

		boolean isHandRendering = ((WorldRendererAccess) client.worldRenderer).isRenderingHands();
		boolean isItemRendering = ((WorldRendererAccess) client.worldRenderer).isRenderingItems();

		if (isHandRendering || isItemRendering || inGui) {
			ItemStack copiedStack = stack.copy();
			Matrix3f matrixNormalClone = new Matrix3f(matrices.peek().getNormal());
			Matrix4f matrixPositionClone = new Matrix4f(matrices.peek().getModel());
			List<Runnable> immediateRenderer = Lists.newArrayList();

			(isHandRendering ? SpecialModels.HAND_RENDER_QUEUE : isItemRendering ? SpecialModels.ITEM_RENDER_QUEUE : immediateRenderer).add(() -> {
				List<Pair<SpecialModelRenderer, BakedModel>> models = ((BakedModelAccess) model).getModels(null);
				if (!models.isEmpty()) {
					models.forEach((renderPair) -> {
						ShaderProgram shader = SpecialModels.LOADED_SHADERS.get(renderPair.getFirst());
						if (shader != null) {
							RenderSystem.enableBlend();
							RenderSystem.enableDepthTest();
							RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
									GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
							RenderSystem.polygonOffset(3.0F, 3.0F);
							RenderSystem.enablePolygonOffset();
							RenderSystem.setShader(() -> shader);
							client.gameRenderer.getLightmapTextureManager().enable();
							client.gameRenderer.getOverlayTexture().setupOverlayColor();

							Camera camera = client.gameRenderer.getCamera();
							MatrixStack matrix = new MatrixStack();
							if (!inGui) {
								matrix.peek().getModel().rotate(Axis.Y_NEGATIVE.rotationDegrees(180));
								matrix.peek().getModel().rotate(Axis.Y_NEGATIVE.rotationDegrees(camera.getYaw()));
								matrix.peek().getModel().rotate(Axis.X_NEGATIVE.rotationDegrees(camera.getPitch()));
							}
							matrix.peek().getModel().mul(matrixPositionClone);
							matrix.peek().getNormal().mul(matrixNormalClone);

							BufferBuilder bufferBuilder = Tessellator.getInstance().getBufferBuilder();
							bufferBuilder.begin(DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);

							renderPair.getFirst().setup(matrix, shader);

							if (shader.getUniform("renderAsEntity") != null) {
								shader.getUniform("renderAsEntity").setFloat(1.0F);
							}

							if (shader.chunkOffset != null) {
								shader.chunkOffset.setVec3(0.0F, 0.0F, 0.0F);
							}

							for (BakedQuad quad : this.specialModels$getQuads(renderPair.getSecond())) {

								int i = -1;
								if (!copiedStack.isEmpty() && quad.hasColor()) {
									i = this.colors.getColor(copiedStack, quad.getColorIndex());
								}

								float f = (float) (i >> 16 & 0xFF) / 255.0F;
								float g = (float) (i >> 8 & 0xFF) / 255.0F;
								float h = (float) (i & 0xFF) / 255.0F;

								bufferBuilder.bakedQuad(matrix.peek(), quad, f, g, h, light, overlay);
							}

							BufferRenderer.drawWithShader(bufferBuilder.end());

							client.gameRenderer.getOverlayTexture().teardownOverlayColor();
							client.gameRenderer.getLightmapTextureManager().disable();
							RenderSystem.polygonOffset(0.0F, 0.0F);
							RenderSystem.disablePolygonOffset();
							RenderSystem.disableBlend();

						}
					});
				}
			});

			immediateRenderer.forEach(Runnable::run);
			immediateRenderer.clear();
		}
	}

	@Inject(method = "Lnet/minecraft/client/render/item/ItemRenderer;renderGuiItemModel(Lnet/minecraft/item/ItemStack;IILnet/minecraft/client/render/model/BakedModel;)V", at = @At("HEAD"))
	private void specialModels$renderGuiItemModel$head(ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
		inGui = true;
	}

	@Inject(method = "Lnet/minecraft/client/render/item/ItemRenderer;renderGuiItemModel(Lnet/minecraft/item/ItemStack;IILnet/minecraft/client/render/model/BakedModel;)V", at = @At("TAIL"))
	private void specialModels$renderGuiItemModel$tail(ItemStack stack, int x, int y, BakedModel model, CallbackInfo ci) {
		inGui = false;
	}

	@Override
	public boolean isInGui() {
		return inGui;
	}

	@Override
	public void setInGui(boolean in) {
		inGui = in;
	}

	@Unique
	private Set<BakedQuad> specialModels$getQuads(BakedModel model) {
		Set<BakedQuad> bakedQuads = Sets.newHashSet();

		RandomGenerator randomGenerator = RandomGenerator.createLegacy();

		for (Direction dir : Direction.values()) {
			randomGenerator.setSeed(42L);
			bakedQuads.addAll(model.getQuads(null, dir, randomGenerator));
		}

		randomGenerator.setSeed(42L);
		bakedQuads.addAll(model.getQuads(null, null, randomGenerator));

		return bakedQuads;
	}

}
