package net.ludocrypt.specialmodels.impl.chunk;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;
import org.quiltmc.loader.api.minecraft.ClientOnly;
import org.slf4j.Logger;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.primitives.Doubles;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormats;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;

import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.ludocrypt.specialmodels.api.SpecialModelRenderer;
import net.ludocrypt.specialmodels.impl.access.BakedModelAccess;
import net.ludocrypt.specialmodels.impl.access.WorldChunkBuilderAccess;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.chunk.ChunkOcclusionData;
import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.client.render.chunk.ChunkRenderRegion;
import net.minecraft.client.render.chunk.ChunkRenderRegionCache;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.random.RandomGenerator;
import net.minecraft.util.thread.TaskExecutor;
import net.minecraft.world.chunk.ChunkStatus;

@ClientOnly
public class SpecialChunkBuilder {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final PriorityBlockingQueue<SpecialChunkBuilder.BuiltChunk.Task> highPriorityChunksToBuild = Queues.newPriorityBlockingQueue();
	private final Queue<SpecialChunkBuilder.BuiltChunk.Task> chunksToBuild = Queues.<SpecialChunkBuilder.BuiltChunk.Task>newLinkedBlockingDeque();
	private int highPriorityQuota = 2;
	private final Queue<SpecialBufferBuilderStorage> threadBuffers;
	private final Queue<Runnable> uploadQueue = Queues.newConcurrentLinkedQueue();
	private volatile int queuedTaskCount;
	private volatile int bufferCount;
	final SpecialBufferBuilderStorage buffers;
	private final TaskExecutor<Runnable> mailbox;
	private final Executor executor;
	ClientWorld world;
	final WorldRenderer worldRenderer;
	private Vec3d cameraPosition = Vec3d.ZERO;
	private MinecraftClient client;

	public SpecialChunkBuilder(ClientWorld world, WorldRenderer worldRenderer, Executor executor, boolean useMaxThreads, SpecialBufferBuilderStorage buffers) {
		this.world = world;
		this.worldRenderer = worldRenderer;
		int i = Math.max(1, (int) ((double) Runtime.getRuntime().maxMemory() * 0.3) / (RenderLayer.getBlockLayers().stream().mapToInt(RenderLayer::getExpectedBufferSize).sum() * 4) - 1);
		int j = Runtime.getRuntime().availableProcessors();
		int k = useMaxThreads ? j : Math.min(j, 4);
		int l = Math.max(1, Math.min(k, i));
		this.buffers = buffers;
		List<SpecialBufferBuilderStorage> list = Lists.<SpecialBufferBuilderStorage>newArrayListWithExpectedSize(l);

		try {
			for (int m = 0; m < l; ++m) {
				list.add(new SpecialBufferBuilderStorage());
			}
		} catch (OutOfMemoryError var14) {
			LOGGER.warn("Allocated only {}/{} buffers", list.size(), l);
			int n = Math.min(list.size() * 2 / 3, list.size() - 1);

			for (int o = 0; o < n; ++o) {
				list.remove(list.size() - 1);
			}

			System.gc();
		}

		this.threadBuffers = Queues.<SpecialBufferBuilderStorage>newArrayDeque(list);
		this.bufferCount = this.threadBuffers.size();
		this.executor = executor;
		this.mailbox = TaskExecutor.create(executor, "Special Chunk Renderer");
		this.mailbox.send(this::scheduleRunTasks);
		this.client = MinecraftClient.getInstance();
	}

	public void setWorld(ClientWorld world) {
		this.world = world;
	}

	private void scheduleRunTasks() {
		if (!this.threadBuffers.isEmpty()) {
			SpecialChunkBuilder.BuiltChunk.Task task = this.getNextBuildTask();
			if (task != null) {
				SpecialBufferBuilderStorage SpecialBufferBuilderStorage = (SpecialBufferBuilderStorage) this.threadBuffers.poll();
				this.queuedTaskCount = this.highPriorityChunksToBuild.size() + this.chunksToBuild.size();
				this.bufferCount = this.threadBuffers.size();
				CompletableFuture.supplyAsync(Util.debugSupplier(task.name(), () -> task.run(SpecialBufferBuilderStorage)), this.executor).thenCompose(future -> future)
						.whenComplete((result, throwable) -> {
							if (throwable != null) {
								MinecraftClient.getInstance().setCrashReport(CrashReport.create(throwable, "Batching chunks"));
							} else {
								this.mailbox.send((Runnable) () -> {
									if (result == SpecialChunkBuilder.Result.SUCCESSFUL) {
										SpecialBufferBuilderStorage.clear();
									} else {
										SpecialBufferBuilderStorage.reset();
									}

									this.threadBuffers.add(SpecialBufferBuilderStorage);
									this.bufferCount = this.threadBuffers.size();
									this.scheduleRunTasks();
								});
							}
						});
			}
		}
	}

	@Nullable
	private SpecialChunkBuilder.BuiltChunk.Task getNextBuildTask() {
		if (this.highPriorityQuota <= 0) {
			SpecialChunkBuilder.BuiltChunk.Task task = (SpecialChunkBuilder.BuiltChunk.Task) this.chunksToBuild.poll();
			if (task != null) {
				this.highPriorityQuota = 2;
				return task;
			}
		}

		SpecialChunkBuilder.BuiltChunk.Task task = (SpecialChunkBuilder.BuiltChunk.Task) this.highPriorityChunksToBuild.poll();
		if (task != null) {
			--this.highPriorityQuota;
			return task;
		} else {
			this.highPriorityQuota = 2;
			return (SpecialChunkBuilder.BuiltChunk.Task) this.chunksToBuild.poll();
		}
	}

	public String getDebugString() {
		return String.format(Locale.ROOT, "pC: %03d, pU: %02d, aB: %02d", this.queuedTaskCount, this.uploadQueue.size(), this.bufferCount);
	}

	public int getToBatchCount() {
		return this.queuedTaskCount;
	}

	public int getChunksToUpload() {
		return this.uploadQueue.size();
	}

	public int getFreeBufferCount() {
		return this.bufferCount;
	}

	public void setCameraPosition(Vec3d cameraPosition) {
		this.cameraPosition = cameraPosition;
	}

	public Vec3d getCameraPosition() {
		return this.cameraPosition;
	}

	public void upload() {
		Runnable runnable;
		while ((runnable = (Runnable) this.uploadQueue.poll()) != null) {
			runnable.run();
		}
	}

	public void rebuild(SpecialChunkBuilder.BuiltChunk chunk, ChunkRenderRegionCache chunkRenderRegionCache) {
		chunk.rebuild(chunkRenderRegionCache);
	}

	public void reset() {
		this.clear();
	}

	public void send(SpecialChunkBuilder.BuiltChunk.Task task) {
		this.mailbox.send((Runnable) () -> {
			if (task.highPriority) {
				this.highPriorityChunksToBuild.offer(task);
			} else {
				this.chunksToBuild.offer(task);
			}

			this.queuedTaskCount = this.highPriorityChunksToBuild.size() + this.chunksToBuild.size();
			this.scheduleRunTasks();
		});
	}

	public CompletableFuture<Void> scheduleUpload(BufferBuilder.RenderedBuffer renderedBuffer, VertexBuffer glBuffer) {
		return CompletableFuture.runAsync(() -> {
			if (!glBuffer.invalid()) {
				glBuffer.bind();
				glBuffer.upload(renderedBuffer);
				VertexBuffer.unbind();
			}
		}, this.uploadQueue::add);
	}

	private void clear() {
		while (!this.highPriorityChunksToBuild.isEmpty()) {
			SpecialChunkBuilder.BuiltChunk.Task task = (SpecialChunkBuilder.BuiltChunk.Task) this.highPriorityChunksToBuild.poll();
			if (task != null) {
				task.cancel();
			}
		}

		while (!this.chunksToBuild.isEmpty()) {
			SpecialChunkBuilder.BuiltChunk.Task task = (SpecialChunkBuilder.BuiltChunk.Task) this.chunksToBuild.poll();
			if (task != null) {
				task.cancel();
			}
		}

		this.queuedTaskCount = 0;
	}

	public boolean isEmpty() {
		return this.queuedTaskCount == 0 && this.uploadQueue.isEmpty();
	}

	public void stop() {
		this.clear();
		this.mailbox.close();
		this.threadBuffers.clear();
	}

	public class BuiltChunk {
		public static final int SIZE = 16;
		public final int index;
		public final AtomicReference<SpecialChunkBuilder.ChunkData> data = new AtomicReference<SpecialChunkBuilder.ChunkData>(SpecialChunkBuilder.ChunkData.EMPTY);
		final AtomicInteger cancelledInitialBuilds = new AtomicInteger(0);
		@Nullable
		private SpecialChunkBuilder.BuiltChunk.RebuildTask rebuildTask;

		private Box boundingBox;
		private boolean needsRebuild = true;
		final BlockPos.Mutable origin = new BlockPos.Mutable(-1, -1, -1);
		private final BlockPos.Mutable[] neighborPositions = Util.make(new BlockPos.Mutable[6], mutablePositions -> {
			for (int i = 0; i < mutablePositions.length; ++i) {
				mutablePositions[i] = new BlockPos.Mutable();
			}
		});
		private boolean needsImportantRebuild;

		private final Map<SpecialModelRenderer, VertexBuffer> specialModelBuffers = SpecialModelRenderer.SPECIAL_MODEL_RENDERER.getEntries().stream()
				.collect(Collectors.toMap(entry -> entry.getValue(), entry -> new VertexBuffer(VertexBuffer.Usage.STATIC)));

		public VertexBuffer getBuffer(SpecialModelRenderer modelRenderer) {
			return specialModelBuffers.get(modelRenderer);
		}

		public Map<SpecialModelRenderer, VertexBuffer> getSpecialModelBuffers() {
			return specialModelBuffers;
		}

		public BuiltChunk(int index, int x, int y, int z) {
			this.index = index;
			this.setOrigin(x, y, z);
		}

		private boolean isChunkNonEmpty(BlockPos pos) {
			return SpecialChunkBuilder.this.world.getChunk(ChunkSectionPos.getSectionCoord(pos.getX()), ChunkSectionPos.getSectionCoord(pos.getZ()), ChunkStatus.FULL, false) != null;
		}

		public boolean shouldBuild() {
			if (!(this.getSquaredCameraDistance() > 576.0)) {
				return true;
			} else {
				return this.isChunkNonEmpty(this.neighborPositions[Direction.WEST.ordinal()]) && this.isChunkNonEmpty(this.neighborPositions[Direction.NORTH.ordinal()])
						&& this.isChunkNonEmpty(this.neighborPositions[Direction.EAST.ordinal()]) && this.isChunkNonEmpty(this.neighborPositions[Direction.SOUTH.ordinal()]);
			}
		}

		public Box getBoundingBox() {
			return this.boundingBox;
		}

		public void setOrigin(int x, int y, int z) {
			this.clear();
			this.origin.set(x, y, z);
			this.boundingBox = new Box((double) x, (double) y, (double) z, (double) (x + 16), (double) (y + 16), (double) (z + 16));

			for (Direction direction : Direction.values()) {
				this.neighborPositions[direction.ordinal()].set(this.origin).move(direction, 16);
			}
		}

		protected double getSquaredCameraDistance() {
			Camera camera = client.gameRenderer.getCamera();
			double d = this.boundingBox.minX + 8.0 - camera.getPos().x;
			double e = this.boundingBox.minY + 8.0 - camera.getPos().y;
			double f = this.boundingBox.minZ + 8.0 - camera.getPos().z;
			return d * d + e * e + f * f;
		}

		void beginBufferBuilding(BufferBuilder buffer) {
			buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
		}

		public SpecialChunkBuilder.ChunkData getData() {
			return (SpecialChunkBuilder.ChunkData) this.data.get();
		}

		private void clear() {
			this.cancel();
			this.data.set(SpecialChunkBuilder.ChunkData.EMPTY);
			this.needsRebuild = true;
		}

		public void delete() {
			this.clear();
			this.specialModelBuffers.values().forEach(VertexBuffer::close);
		}

		public BlockPos getOrigin() {
			return this.origin;
		}

		public void scheduleRebuild(boolean important) {
			boolean bl = this.needsRebuild;
			this.needsRebuild = true;
			this.needsImportantRebuild = important | (bl && this.needsImportantRebuild);
		}

		public void cancelRebuild() {
			this.needsRebuild = false;
			this.needsImportantRebuild = false;
		}

		public boolean needsRebuild() {
			return this.needsRebuild;
		}

		public boolean needsImportantRebuild() {
			return this.needsRebuild && this.needsImportantRebuild;
		}

		public BlockPos getNeighborPosition(Direction direction) {
			return this.neighborPositions[direction.ordinal()];
		}

		protected boolean cancel() {
			boolean bl = false;
			if (this.rebuildTask != null) {
				this.rebuildTask.cancel();
				this.rebuildTask = null;
				bl = true;
			}

			return bl;
		}

		public SpecialChunkBuilder.BuiltChunk.Task createRebuildTask(ChunkRenderRegionCache renderRegionCache) {
			boolean bl = this.cancel();
			BlockPos blockPos = this.origin.toImmutable();
			ChunkRenderRegion chunkRenderRegion = renderRegionCache.createRenderRegion(SpecialChunkBuilder.this.world, blockPos.add(-1, -1, -1), blockPos.add(16, 16, 16), 1);
			boolean bl2 = this.data.get() == SpecialChunkBuilder.ChunkData.EMPTY;
			if (bl2 && bl) {
				this.cancelledInitialBuilds.incrementAndGet();
			}

			this.rebuildTask = new SpecialChunkBuilder.BuiltChunk.RebuildTask(this.getSquaredCameraDistance(), chunkRenderRegion, !bl2 || this.cancelledInitialBuilds.get() > 2);
			return this.rebuildTask;
		}

		public void scheduleRebuild(SpecialChunkBuilder chunkRenderer, ChunkRenderRegionCache renderRegionCache) {
			SpecialChunkBuilder.BuiltChunk.Task task = this.createRebuildTask(renderRegionCache);
			chunkRenderer.send(task);
		}

		public void rebuild(ChunkRenderRegionCache renderRegionCache) {
			SpecialChunkBuilder.BuiltChunk.Task task = this.createRebuildTask(renderRegionCache);
			task.run(SpecialChunkBuilder.this.buffers);
		}

		public class RebuildTask extends SpecialChunkBuilder.BuiltChunk.Task {
			@Nullable
			protected ChunkRenderRegion region;

			public RebuildTask(double distance, @Nullable ChunkRenderRegion region, boolean highPriority) {
				super(distance, highPriority);
				this.region = region;
			}

			@Override
			protected String name() {
				return "rend_chk_rebuild";
			}

			@Override
			public CompletableFuture<SpecialChunkBuilder.Result> run(SpecialBufferBuilderStorage buffers) {
				if (this.cancelled.get()) {
					return CompletableFuture.completedFuture(SpecialChunkBuilder.Result.CANCELLED);
				} else if (!BuiltChunk.this.shouldBuild()) {
					this.region = null;
					BuiltChunk.this.scheduleRebuild(false);
					this.cancelled.set(true);
					return CompletableFuture.completedFuture(SpecialChunkBuilder.Result.CANCELLED);
				} else if (this.cancelled.get()) {
					return CompletableFuture.completedFuture(SpecialChunkBuilder.Result.CANCELLED);
				} else {

					Vec3d vec3d = SpecialChunkBuilder.this.getCameraPosition();
					float f = (float) vec3d.x;
					float g = (float) vec3d.y;
					float h = (float) vec3d.z;
					SpecialChunkBuilder.BuiltChunk.RebuildTask.RenderedChunkData renderedChunkData = this.render(f, g, h, buffers);

					if (this.cancelled.get()) {
						renderedChunkData.renderedBuffers.values().forEach(BufferBuilder.RenderedBuffer::release);
						return CompletableFuture.completedFuture(SpecialChunkBuilder.Result.CANCELLED);
					} else {
						SpecialChunkBuilder.ChunkData chunkData = new SpecialChunkBuilder.ChunkData();
						chunkData.occlusionGraph = renderedChunkData.occlusionGraph;

						chunkData.bufferState = renderedChunkData.bufferState;
						List<CompletableFuture<Void>> list = Lists.newArrayList();

						renderedChunkData.renderedBuffers
								.forEach((modelRenderer, renderedBuffer) -> list.add(SpecialChunkBuilder.this.scheduleUpload(renderedBuffer, BuiltChunk.this.getBuffer(modelRenderer))));

						return Util.combine(list).handle((listx, throwable) -> {
							if (throwable != null && !(throwable instanceof CancellationException) && !(throwable instanceof InterruptedException)) {
								MinecraftClient.getInstance().setCrashReport(CrashReport.create(throwable, "Rendering chunk"));
							}

							if (this.cancelled.get()) {
								return SpecialChunkBuilder.Result.CANCELLED;
							} else {
								BuiltChunk.this.data.set(chunkData);
								BuiltChunk.this.cancelledInitialBuilds.set(0);
								((WorldChunkBuilderAccess) (SpecialChunkBuilder.this.worldRenderer)).addSpecialBuiltChunk(BuiltChunk.this);
								return SpecialChunkBuilder.Result.SUCCESSFUL;
							}
						});
					}
				}
			}

			private SpecialChunkBuilder.BuiltChunk.RebuildTask.RenderedChunkData render(float cameraX, float cameraY, float cameraZ, SpecialBufferBuilderStorage buffers) {
				SpecialChunkBuilder.BuiltChunk.RebuildTask.RenderedChunkData renderedChunkData = new SpecialChunkBuilder.BuiltChunk.RebuildTask.RenderedChunkData();
				BlockPos originPos = BuiltChunk.this.origin.toImmutable();
				BlockPos boundingPos = originPos.add(15, 15, 15);
				ChunkOcclusionDataBuilder chunkOcclusionDataBuilder = new ChunkOcclusionDataBuilder();
				ChunkRenderRegion chunkRenderRegion = this.region;
				this.region = null;
				MatrixStack matrixStack = new MatrixStack();

				if (chunkRenderRegion != null) {

					BlockModelRenderer.enableBrightnessCache();

					RandomGenerator randomGenerator = RandomGenerator.createLegacy();
					BlockRenderManager blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();

					for (BlockPos pos : BlockPos.iterate(originPos, boundingPos)) {
						BlockState state = chunkRenderRegion.getBlockState(pos);
						if (state.isOpaqueFullCube(chunkRenderRegion, pos)) {
							chunkOcclusionDataBuilder.markClosed(pos);
						}

						if (state.getRenderType() != BlockRenderType.INVISIBLE) {
							matrixStack.push();
							matrixStack.translate((float) (pos.getX() & 15), (float) (pos.getY() & 15), (float) (pos.getZ() & 15));

							List<Pair<SpecialModelRenderer, BakedModel>> models = ((BakedModelAccess) blockRenderManager.getModel(state)).getModels(state);
							if (!models.isEmpty()) {

								for (Pair<SpecialModelRenderer, BakedModel> pair : models) {
									SpecialModelRenderer modelRenderer = pair.getFirst();
									BakedModel model = pair.getSecond();

									long modelSeed = state.getRenderingSeed(pos);

									BufferBuilder buffer = buffers.get(modelRenderer);
									if (!buffer.isBuilding()) {
										buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
									}

									blockRenderManager.getModelRenderer().render(chunkRenderRegion, model, state, pos, matrixStack, buffer, true, randomGenerator, modelSeed,
											OverlayTexture.DEFAULT_UV);
								}
							}

							matrixStack.pop();
						}
					}

					for (SpecialModelRenderer modelRenderer : buffers.getSpecialModelBuffers().keySet()) {
						if (!buffers.get(modelRenderer).isBuilding()) {
							buffers.get(modelRenderer).begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
						}

						BufferBuilder.RenderedBuffer renderedBuffer = buffers.get(modelRenderer).end();
						if (renderedBuffer != null) {
							renderedChunkData.renderedBuffers.put(modelRenderer, renderedBuffer);
						}
					}

					BlockModelRenderer.disableBrightnessCache();
				}

				renderedChunkData.occlusionGraph = chunkOcclusionDataBuilder.build();
				return renderedChunkData;
			}

			@Override
			public void cancel() {
				this.region = null;
				if (this.cancelled.compareAndSet(false, true)) {
					BuiltChunk.this.scheduleRebuild(false);
				}
			}

			public static final class RenderedChunkData {
				public final Map<SpecialModelRenderer, BufferBuilder.RenderedBuffer> renderedBuffers = new Reference2ObjectArrayMap<>();
				public ChunkOcclusionData occlusionGraph = new ChunkOcclusionData();
				@Nullable
				public BufferBuilder.SortState bufferState;

				RenderedChunkData() {
				}
			}
		}

		abstract class Task implements Comparable<SpecialChunkBuilder.BuiltChunk.Task> {
			protected final double distance;
			protected final AtomicBoolean cancelled = new AtomicBoolean(false);
			protected final boolean highPriority;

			public Task(double distance, boolean highPriority) {
				this.distance = distance;
				this.highPriority = highPriority;
			}

			public abstract CompletableFuture<SpecialChunkBuilder.Result> run(SpecialBufferBuilderStorage buffers);

			public abstract void cancel();

			protected abstract String name();

			public int compareTo(SpecialChunkBuilder.BuiltChunk.Task task) {
				return Doubles.compare(this.distance, task.distance);
			}
		}
	}

	public static class ChunkData {
		public static final SpecialChunkBuilder.ChunkData EMPTY = new SpecialChunkBuilder.ChunkData() {
			@Override
			public boolean isVisibleThrough(Direction from, Direction to) {
				return false;
			}
		};
		final Set<RenderLayer> nonEmptyLayers = new ObjectArraySet<>(RenderLayer.getBlockLayers().size());

		ChunkOcclusionData occlusionGraph = new ChunkOcclusionData();
		@Nullable
		BufferBuilder.SortState bufferState;

		public boolean isEmpty() {
			return this.nonEmptyLayers.isEmpty();
		}

		public boolean isEmpty(RenderLayer layer) {
			return !this.nonEmptyLayers.contains(layer);
		}

		public boolean isVisibleThrough(Direction from, Direction to) {
			return this.occlusionGraph.isVisibleThrough(from, to);
		}
	}

	public static enum Result {
		SUCCESSFUL,
		CANCELLED;
	}

	public static class ChunkInfo {
		public final BuiltChunk chunk;
		private byte direction;
		public byte cullingState;
		public final int propagationLevel;

		public ChunkInfo(BuiltChunk chunk, @Nullable Direction direction, int propagationLevel) {
			this.chunk = chunk;
			if (direction != null) {
				this.addDirection(direction);
			}

			this.propagationLevel = propagationLevel;
		}

		public void updateCullingState(byte parentCullingState, Direction from) {
			this.cullingState = (byte) (this.cullingState | parentCullingState | 1 << from.ordinal());
		}

		public boolean canCull(Direction from) {
			return (this.cullingState & 1 << from.ordinal()) > 0;
		}

		public void addDirection(Direction direction) {
			this.direction = (byte) (this.direction | this.direction | 1 << direction.ordinal());
		}

		public boolean hasDirection(int ordinal) {
			return (this.direction & 1 << ordinal) > 0;
		}

		public boolean hasAnyDirection() {
			return this.direction != 0;
		}

		public boolean method_49633(int i, int j, int k) {
			BlockPos blockPos = this.chunk.getOrigin();
			return i == blockPos.getX() / 16 || k == blockPos.getZ() / 16 || j == blockPos.getY() / 16;
		}

		public int hashCode() {
			return this.chunk.getOrigin().hashCode();
		}

		public boolean equals(Object object) {
			if (!(object instanceof ChunkInfo)) {
				return false;
			} else {
				ChunkInfo chunkInfo = (ChunkInfo) object;
				return this.chunk.getOrigin().equals(chunkInfo.chunk.getOrigin());
			}
		}
	}

	public static class ChunkInfoListMap {
		private final ChunkInfo[] current;

		ChunkInfoListMap(int size) {
			this.current = new ChunkInfo[size];
		}

		public void setInfo(BuiltChunk chunk, ChunkInfo info) {
			this.current[chunk.index] = info;
		}

		@Nullable
		public ChunkInfo getInfo(BuiltChunk chunk) {
			int i = chunk.index;
			return i >= 0 && i < this.current.length ? this.current[i] : null;
		}
	}

	public static class RenderableChunks {
		public final ChunkInfoListMap builtChunkMap;
		public final LinkedHashSet<ChunkInfo> builtChunks;

		public RenderableChunks(int size) {
			this.builtChunkMap = new ChunkInfoListMap(size);
			this.builtChunks = new LinkedHashSet<ChunkInfo>(size);
		}
	}
}
