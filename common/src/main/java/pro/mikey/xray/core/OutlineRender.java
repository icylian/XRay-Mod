package pro.mikey.xray.core;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.LogicOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pro.mikey.xray.XRay;

import java.io.Closeable;
import java.util.*;

public class OutlineRender {
    public static boolean requestedRefresh = false;

	// Custom lines pipeline: distance fade via ModelOffset, vein inversion via a negative
	// red channel in ColorModulator (see xray_lines.vsh/fsh)
	public static RenderPipeline LINES_NO_DEPTH = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(XRay.id("pipeline/xray_lines"))
			.withVertexShader(Identifier.fromNamespaceAndPath(XRay.MOD_ID, "core/xray_lines"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(XRay.MOD_ID, "core/xray_lines"))
			.withBlend(BlendFunction.TRANSLUCENT)
			.withCull(false)
			.withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withColorLogic(LogicOp.NONE)
			.build();

	private static final RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.LINES);
	private static final Map<ChunkPos, VBOHolder> vertexBuffers = new HashMap<>();

	// Separate buffer for the highlighted vein (drawn with inverted colors)
	private static VBOHolder veinBuffer = null;
	private static int veinBufferBuiltFor = -1; // rebuild when the highlighted set changes

	private static final Vector4f TINT_NORMAL = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
	private static final Vector4f TINT_INVERT = new Vector4f(-1.0f, 1.0f, 1.0f, 1.0f);

	private static final Set<ChunkPos> chunksToRefresh = Collections.synchronizedSet(new HashSet<>());

	public static void renderBlocks(PoseStack poseStack) {
		if (!ScanController.INSTANCE.isXRayActive() || Minecraft.getInstance().player == null) {
			return;
		}

		if (ScanController.INSTANCE.syncRenderList.isEmpty()) {
			return;
		}

		if (!chunksToRefresh.isEmpty()) {
			// Clear the vertex buffers for the chunks that need to be refreshed
			for (ChunkPos pos : chunksToRefresh) {
				VBOHolder holder = vertexBuffers.remove(pos);
				if (holder != null) {
					holder.close();
				}
			}

			chunksToRefresh.clear();
		}

		// Vein highlight layer: which blocks are highlighted is decided by VeinHighlighter
		// (cached). The vein VBO is only rebuilt when the highlighted set actually changes.
		Set<Long> veinBlocks = ScanController.INSTANCE.veinHighlighter.getHighlightedBlocks();
		int veinHash = veinBlocks.hashCode();
		if (veinHash != veinBufferBuiltFor) {
			rebuildVeinBuffer(veinBlocks);
			veinBufferBuiltFor = veinHash;
		}

		// Clone the entrySet to avoid concurrent modification exceptions
		var entries = new ArrayList<>(ScanController.INSTANCE.syncRenderList.entrySet());

		Vec3 playerPos = Minecraft.getInstance().gameRenderer.getMainCamera().position().reverse();

		RenderTarget renderTarget = Minecraft.getInstance().getMainRenderTarget();
		if (renderTarget.getColorTexture() == null) {
			return;
		}

		Matrix4fStack matrix4fStack = RenderSystem.getModelViewStack();
		GpuTextureView colorTextureView = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
		GpuTextureView depthTextureView = Minecraft.getInstance().getMainRenderTarget().getDepthTextureView();

		// ModelOffset is repurposed to carry the distance fade parameters (start, end, min alpha)
		DistanceFade fade = DistanceFade.current();

		for (var chunkWithBlockData : entries) {
			var chunkPos = chunkWithBlockData.getKey();
			var blocksWithProps = chunkWithBlockData.getValue();

			if (blocksWithProps.isEmpty()) {
				continue;
			}

			VBOHolder holder = vertexBuffers.get(chunkPos);
			if (holder == null) {
				BufferBuilder bufferBuilder = Tesselator.getInstance().begin(LINES_NO_DEPTH.getVertexFormatMode(), LINES_NO_DEPTH.getVertexFormat());

				// More concurrent modification exceptions can happen here, so we clone the list
				var blockPropsClone = new ArrayList<>(blocksWithProps);

				for (var blockProps : blockPropsClone) {
					if (blockProps == null) {
						continue;
					}

					final int x = blockProps.x(), y = blockProps.y(), z = blockProps.z();
					final int color = blockProps.color();

					// Expand the unit cube into 12 line segments (ShapeRenderer.renderLineBox
					// no longer exists in 1.21.11)
					Shapes.block().forAllEdges((x1, y1, z1, x2, y2, z2) -> {
						bufferBuilder.addVertex((float) (x + x1), (float) (y + y1), (float) (z + z1))
								.setColor(color)
								.setNormal((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
						bufferBuilder.addVertex((float) (x + x2), (float) (y + y2), (float) (z + z2))
								.setColor(color)
								.setNormal((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
					});
				}

				try (MeshData meshData = bufferBuilder.buildOrThrow()) {
					int indexCount = meshData.drawState().indexCount();
					GpuBuffer vertexBuffer = RenderSystem.getDevice()
							.createBuffer(() -> "Xray vertex buffer", GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer());

					vertexBuffers.put(chunkPos, new VBOHolder(vertexBuffer, indexCount));
				}
			}

			holder = vertexBuffers.get(chunkPos);
			if (holder == null || holder.vertexBuffer == null || holder.indexCount == 0) {
				continue;
			}

			matrix4fStack.pushMatrix();
			matrix4fStack.translate((float) playerPos.x(), (float) playerPos.y(), (float) playerPos.z());
			GpuBufferSlice[] gpubufferslice = RenderSystem.getDynamicUniforms().writeTransforms(new DynamicUniforms.Transform(RenderSystem.getModelViewMatrix(), TINT_NORMAL, new Vector3f(fade.start(), fade.end(), fade.minAlpha()), new Matrix4f()));

			drawPass(colorTextureView, depthTextureView, holder.vertexBuffer, holder.indexCount, gpubufferslice[0], "xray");

			matrix4fStack.popMatrix();
		}

		// Vein overlay pass: draws the highlighted vein with inverted colors on top of the
		// regular outlines (depth test is off, so this fully covers them up close)
		if (veinBuffer != null && veinBuffer.indexCount > 0) {
			matrix4fStack.pushMatrix();
			matrix4fStack.translate((float) playerPos.x(), (float) playerPos.y(), (float) playerPos.z());
			GpuBufferSlice[] veinUniforms = RenderSystem.getDynamicUniforms().writeTransforms(new DynamicUniforms.Transform(RenderSystem.getModelViewMatrix(), TINT_INVERT, new Vector3f(fade.start(), fade.end(), Math.max(fade.minAlpha(), 0.6f)), new Matrix4f()));

			drawPass(colorTextureView, depthTextureView, veinBuffer.vertexBuffer, veinBuffer.indexCount, veinUniforms[0], "xray-vein");

			matrix4fStack.popMatrix();
		}
	}

	private static void drawPass(GpuTextureView colorTextureView, GpuTextureView depthTextureView,
								 GpuBuffer vertexBuffer, int indexCount, GpuBufferSlice uniforms, String passName) {
		GpuBuffer gpuBuffer = indices.getBuffer(indexCount);
		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> passName, colorTextureView, OptionalInt.empty(), depthTextureView, OptionalDouble.empty())) {

			renderPass.setPipeline(LINES_NO_DEPTH);
			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setVertexBuffer(0, vertexBuffer);
			renderPass.setIndexBuffer(gpuBuffer, indices.type());
			renderPass.setUniform("DynamicTransforms", uniforms);
			renderPass.drawIndexed(0, 0, indexCount, 1);
		}
	}

	/**
	 * Builds the small vertex buffer holding only the highlighted vein's outlines.
	 * Called at most a few times a second (when the highlighted set changes); the vein
	 * blocks stay in their chunk VBOs too and are simply overdrawn here.
	 */
	private static void rebuildVeinBuffer(Set<Long> veinBlocks) {
		if (veinBuffer != null) {
			veinBuffer.close();
			veinBuffer = null;
		}

		if (veinBlocks.isEmpty()) {
			return;
		}

		// Look up colors from the render list
		List<OutlineRenderTarget> veinTargets = new ArrayList<>();
		synchronized (ScanController.INSTANCE.syncRenderList) {
			for (var entry : ScanController.INSTANCE.syncRenderList.entrySet()) {
				for (OutlineRenderTarget target : entry.getValue()) {
					if (veinBlocks.contains(BlockPos.asLong(target.x(), target.y(), target.z()))) {
						veinTargets.add(target);
					}
				}
			}
		}

		if (veinTargets.isEmpty()) {
			return;
		}

		BufferBuilder bufferBuilder = Tesselator.getInstance().begin(LINES_NO_DEPTH.getVertexFormatMode(), LINES_NO_DEPTH.getVertexFormat());

		for (OutlineRenderTarget target : veinTargets) {
			final int x = target.x(), y = target.y(), z = target.z();
			// The shader inverts the RGB but keeps this color's original alpha
			final int color = target.color();

			Shapes.block().forAllEdges((x1, y1, z1, x2, y2, z2) -> {
				bufferBuilder.addVertex((float) (x + x1), (float) (y + y1), (float) (z + z1))
						.setColor(color)
						.setNormal((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
				bufferBuilder.addVertex((float) (x + x2), (float) (y + y2), (float) (z + z2))
						.setColor(color)
						.setNormal((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
			});
		}

		try (MeshData meshData = bufferBuilder.buildOrThrow()) {
			int indexCount = meshData.drawState().indexCount();
			GpuBuffer vertexBuffer = RenderSystem.getDevice()
					.createBuffer(() -> "Xray vein buffer", GpuBuffer.USAGE_VERTEX, meshData.vertexBuffer());

			veinBuffer = new VBOHolder(vertexBuffer, indexCount);
		}
	}

	public static void clearVBOs() {
		for (VBOHolder holder : vertexBuffers.values()) {
			if (holder != null) {
				holder.close();
			}
		}
		vertexBuffers.clear();

		if (veinBuffer != null) {
			veinBuffer.close();
			veinBuffer = null;
		}
		veinBufferBuiltFor = -1;
	}

	public static void clearVBOsFor(List<ChunkPos> removedChunks) {
		if (removedChunks.isEmpty()) {
			return;
		}

		chunksToRefresh.addAll(removedChunks);
	}

	public static void refreshVBOForChunk(ChunkPos pos) {
		chunksToRefresh.add(pos);
	}

	private record VBOHolder(GpuBuffer vertexBuffer, int indexCount) implements Closeable {

		@Override
		public void close() {
			if (vertexBuffer != null) {
				vertexBuffer.close();
			}
		}
	}

	/**
	 * Distance fade parameters for the outline shader: outlines stay fully opaque up to
	 * {@code start} blocks from the camera, then fade linearly down to {@code minAlpha}
	 * at {@code end} blocks.
	 */
	public record DistanceFade(float start, float end, float minAlpha) {
		public static DistanceFade current() {
			var config = XRay.config();
			return new DistanceFade(
				config.fadeStartDistance.get(),
				config.fadeEndDistance.get(),
				config.fadeMinAlpha.get() / 100.0f
			);
		}
	}
}
