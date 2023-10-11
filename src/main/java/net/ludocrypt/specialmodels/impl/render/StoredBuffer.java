package net.ludocrypt.specialmodels.impl.render;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GlStateManager.Viewport;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

public class StoredBuffer {

	private final int id;
	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final Matrix4f projectionMatrix;
	private final VertexSorting sorting;

	public StoredBuffer(int id, int x, int y, int width, int height, Matrix4f projectionMatrix, VertexSorting sorting) {
		this.id = id;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.projectionMatrix = projectionMatrix;
		this.sorting = sorting;
	}

	public static StoredBuffer store() {
		return new StoredBuffer(GlStateManager.getBoundFramebuffer(), Viewport.getX(), Viewport.getY(), Viewport.getWidth(), Viewport.getHeight(), RenderSystem.getProjectionMatrix(),
				RenderSystem.getVertexSorting());
	}

	public void restore() {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.id);
		GlStateManager._viewport(this.x, this.y, this.width, this.height);
		RenderSystem.setProjectionMatrix(this.projectionMatrix, this.sorting);
	}

	public int getId() {
		return id;
	}

	public int getX() {
		return x;
	}

	public int getY() {
		return y;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public Matrix4f getProjectionMatrix() {
		return projectionMatrix;
	}

	public VertexSorting getSorting() {
		return sorting;
	}

}
