package net.ccbluex.liquidbounce.ultralight

import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.CommandEncoder
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.textures.AddressMode
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.opengl.GlSampler
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.janrupf.ujr.api.bitmap.UlBitmapFormat
import net.janrupf.ujr.api.bitmap.UltralightBitmap
import net.janrupf.ujr.api.gpu.UlBlendEquation
import net.janrupf.ujr.api.gpu.UlBlendFactor
import net.janrupf.ujr.api.gpu.UlCommand
import net.janrupf.ujr.api.gpu.UlCommandList
import net.janrupf.ujr.api.gpu.UlCommandType
import net.janrupf.ujr.api.gpu.UlGPUState
import net.janrupf.ujr.api.gpu.UlRenderBuffer
import net.janrupf.ujr.api.gpu.UlTextureFlags
import net.janrupf.ujr.api.gpu.UlVertexBufferFormat
import net.janrupf.ujr.api.gpu.UltralightGPUDriver
import net.janrupf.ujr.api.math.IntRect
import net.minecraft.client.gui.render.TextureSetup
import org.apache.logging.log4j.LogManager
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.opengl.GL33C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional
import java.util.OptionalDouble
import java.util.function.Supplier

/**
 * Size of the uniform block of Ultralight's shaders.
 */
private const val UNIFORMS_SIZE = 800L

private val PASS_LABEL = Supplier { "Ultralight" }
private val VERTICES_LABEL = Supplier { "Ultralight vertices" }
private val INDICES_LABEL = Supplier { "Ultralight indices" }
private val TRANSPARENT = Vector4f(0f)

/**
 * Draws the pages of Ultralight with the renderer of the game, so they never leave the GPU.
 *
 * Ultralight calls the driver on the render thread during [net.janrupf.ujr.api.UltralightRenderer.render], and the
 * command lists are drawn right away. Textures hold RGBA, so BGRA bitmaps are swizzled on upload.
 *
 * No exception may leave the driver, Ultralight's renderer can't draw anymore after one unwound through it.
 */
@Suppress("TooManyFunctions")
internal class UltralightGpuDriver : UltralightGPUDriver, AutoCloseable {

    private class Texture(
        val texture: GpuTexture,
        val view: GpuTextureView,
        val bitmapFormat: UlBitmapFormat,
        val isRenderTarget: Boolean
    ) {

        /**
         * Whether the texture holds anything yet, render targets are undefined until Ultralight draws into them.
         */
        var isReady = !isRenderTarget

        val isDataPage
            get() = bitmapFormat in DATA_PAGE_FORMATS

        fun close() {
            view.close()
            texture.close()
        }

    }

    private class Geometry(var vertices: GpuBuffer, var indices: GpuBuffer) {

        fun close() {
            vertices.close()
            indices.close()
        }

    }

    private val logger = LogManager.getLogger("LiquidBounce/Ultralight")
    private var hasFailed = false

    private val textures = Int2ObjectOpenHashMap<Texture>()
    private val renderBufferTextures = Int2IntOpenHashMap()
    private val geometries = Int2ObjectOpenHashMap<Geometry>()

    private var lastTextureId = 0
    private var lastRenderBufferId = 0
    private var lastGeometryId = 0

    /**
     * Bound to the samplers a draw doesn't use, as the pipelines need all of them. Photon's index page is an integer
     * texture, which needs an integer one.
     */
    private val emptyTexture = createEmptyTexture(GpuFormat.RGBA8_UNORM, 4)
    private val emptyIntegerTexture = createEmptyTexture(GpuFormat.RGBA16_UINT, 8)

    private val linearSampler
        get() = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)

    /**
     * Reads Photon's data pages. The game's samplers always filter between mipmaps on OpenGL, which leaves an integer
     * texture incomplete so it reads back as zero, hence the plain NEAREST. On Vulkan, a max LOD of 0 already turns
     * mipmap filtering off.
     */
    private val dataPageSampler = gpuDevice.createSampler(
        AddressMode.CLAMP_TO_EDGE,
        AddressMode.CLAMP_TO_EDGE,
        FilterMode.NEAREST,
        FilterMode.NEAREST,
        1,
        OptionalDouble.of(0.0)
    ).also {
        if (it is GlSampler) {
            GL33C.glSamplerParameteri(it.id, GL33C.GL_TEXTURE_MIN_FILTER, GL33C.GL_NEAREST)
        }
    }

    private val transform = Matrix4f()
    private val projection = Matrix4f()
    private val matrixValues = FloatArray(16)

    /**
     * Finds the texture Ultralight drew into, once it holds a page.
     */
    fun textureSetup(textureId: Int): TextureSetup? {
        val texture = textures[textureId]?.takeIf { it.isReady } ?: return null
        return TextureSetup.singleTexture(texture.view, linearSampler)
    }

    override fun beginSynchronize() {
        // Everything is drawn in order on the render thread
    }

    override fun endSynchronize() {
        // Everything is drawn in order on the render thread
    }

    override fun nextTextureId() = ++lastTextureId

    override fun createTexture(textureId: Int, bitmap: UltralightBitmap, flags: Int) = guarded("create a texture") {
        val isRenderTarget = flags and UlTextureFlags.RENDER_TARGET != 0
        val texture = createTexture(bitmap, isRenderTarget)
        if (!isRenderTarget) {
            upload(texture, bitmap, IntRect(0, 0, bitmap.width().toInt(), bitmap.height().toInt()))
        }
        textures.put(textureId, texture)?.close()
    }

    override fun updateTexture(textureId: Int, bitmap: UltralightBitmap, dirtyRect: IntRect) =
        guarded("update a texture") {
            val texture = textures[textureId]
            if (texture == null || texture.texture.getWidth(0) != bitmap.width().toInt() ||
                texture.texture.getHeight(0) != bitmap.height().toInt() || texture.bitmapFormat != bitmap.format()) {
                createTexture(textureId, bitmap, 0)
                return@guarded
            }

            upload(texture, bitmap, dirtyRect)
        }

    override fun destroyTexture(textureId: Int) = guarded("destroy a texture") {
        textures.remove(textureId)?.close()
    }

    override fun nextRenderBufferId() = ++lastRenderBufferId

    override fun createRenderBuffer(renderBufferId: Int, renderBuffer: UlRenderBuffer) {
        renderBufferTextures.put(renderBufferId, renderBuffer.textureId())
    }

    override fun destroyRenderBuffer(renderBufferId: Int) {
        renderBufferTextures.remove(renderBufferId)
    }

    override fun nextGeometryId() = ++lastGeometryId

    override fun createGeometry(
        geometryId: Int,
        format: UlVertexBufferFormat,
        vertices: ByteBuffer,
        indices: ByteBuffer
    ) = guarded("create geometry") {
        val geometry = Geometry(
            gpuDevice.createBuffer(VERTICES_LABEL, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, vertices),
            gpuDevice.createBuffer(INDICES_LABEL, GpuBuffer.USAGE_INDEX or GpuBuffer.USAGE_COPY_DST, indices)
        )
        geometries.put(geometryId, geometry)?.close()
    }

    override fun updateGeometry(
        geometryId: Int,
        format: UlVertexBufferFormat,
        vertices: ByteBuffer,
        indices: ByteBuffer
    ) = guarded("update geometry") {
        val geometry = geometries[geometryId]
        if (geometry == null) {
            createGeometry(geometryId, format, vertices, indices)
            return@guarded
        }

        geometry.vertices = write(geometry.vertices, VERTICES_LABEL, GpuBuffer.USAGE_VERTEX, vertices)
        geometry.indices = write(geometry.indices, INDICES_LABEL, GpuBuffer.USAGE_INDEX, indices)
    }

    override fun destroyGeometry(geometryId: Int) = guarded("destroy geometry") {
        geometries.remove(geometryId)?.close()
    }

    override fun updateCommandList(commands: UlCommandList) = guarded("draw a page") {
        val encoder = gpuDevice.createCommandEncoder()

        // Written before any pass begins
        val uniforms = arrayOfNulls<GpuBufferSlice>(commands.size())
        for (i in 0 until commands.size()) {
            val command = commands.get(i)
            if (command.type() != UlCommandType.DRAW_GEOMETRY) continue

            val target = renderTarget(command.state()) ?: continue
            uniforms[i] = writeUniforms(encoder, command.state(), target.texture)
        }

        var pass: RenderPass? = null
        var passRenderBuffer = 0

        try {
            for (i in 0 until commands.size()) {
                val command = commands.get(i)

                when (command.type()) {
                    // Ends the pass, so the next one samples what was drawn so far
                    UlCommandType.FLUSH -> {
                        pass?.close()
                        pass = null
                    }

                    UlCommandType.CLEAR_RENDER_BUFFER -> {
                        val target = renderTarget(command.state()) ?: continue
                        pass?.close()
                        pass = null

                        encoder.clearColorTexture(target.texture, TRANSPARENT)
                        target.isReady = true
                    }

                    UlCommandType.DRAW_GEOMETRY -> {
                        val state = command.state()
                        val target = renderTarget(state) ?: continue
                        val geometry = geometries[command.geometryId()] ?: continue

                        if (pass == null || passRenderBuffer != state.renderBufferId()) {
                            pass?.close()
                            pass = encoder.createRenderPass(PASS_LABEL, target.view, Optional.empty())
                            passRenderBuffer = state.renderBufferId()
                        }

                        draw(pass, command, target, geometry, uniforms[i]!!)
                        target.isReady = true
                    }
                }
            }
        } finally {
            pass?.close()
        }
    }

    private fun draw(pass: RenderPass, command: UlCommand, target: Texture, geometry: Geometry, uniforms: GpuBufferSlice) {
        val state = command.state()

        if (state.enableScissor()) {
            val rect = state.scissorRect()
            val width = target.texture.getWidth(0)
            val height = target.texture.getHeight(0)
            val left = rect.left.coerceIn(0, width)
            val top = rect.top.coerceIn(0, height)
            val right = rect.right.coerceIn(left, width)
            val bottom = rect.bottom.coerceIn(top, height)

            if (right == left || bottom == top) {
                return
            }

            pass.enableScissor(left, top, right - left, bottom - top)
        } else {
            pass.disableScissor()
        }

        val shader = state.shaderType()
        val blend = if (state.enableBlend()) blendFunction(state) else null
        pass.setPipeline(UltralightPipelines.get(shader, target.texture.format, blend))

        for ((name, slot) in UltralightPipelines.programs.getValue(shader).textures) {
            val textureId = when (slot) {
                1 -> state.texture1Id()
                2 -> state.texture2Id()
                3 -> state.texture3Id()
                else -> state.texture4Id()
            }

            // A target can't be sampled while it is drawn into
            val texture = textures[textureId]?.takeUnless { it === target }
            pass.setUniform(name, texture?.view ?: emptyView(slot), sampler(texture, slot))
        }

        pass.setUniform(UNIFORMS, uniforms)
        pass.setVertexBuffer(0, geometry.vertices.slice())
        pass.setIndexBuffer(geometry.indices, IndexType.INT)
        pass.drawIndexed(command.indicesCount(), 1, command.indicesOffset(), 0, 0)
    }

    private fun emptyView(slot: Int) = if (slot == 4) emptyIntegerTexture.view else emptyTexture.view

    private fun sampler(texture: Texture?, slot: Int): GpuSampler =
        if (texture?.isDataPage ?: (slot == 4)) dataPageSampler else linearSampler

    private fun blendFunction(state: UlGPUState): BlendFunction {
        val source = blendFactor(state.blendSrcFactor())
        val destination = blendFactor(state.blendDstFactor())
        val operation = when (state.blendEquation()) {
            UlBlendEquation.ADD -> BlendOp.ADD
            UlBlendEquation.SUBTRACT -> BlendOp.SUBTRACT
            UlBlendEquation.REV_SUBTRACT -> BlendOp.REVERSE_SUBTRACT
            UlBlendEquation.MIN -> BlendOp.MIN
            UlBlendEquation.MAX -> BlendOp.MAX
        }

        val premultiplied = UltralightPipelines.premultiplied
        return if (source == BlendFactor.ONE && destination == BlendFactor.ONE_MINUS_SRC_ALPHA &&
            operation == BlendOp.ADD) premultiplied else BlendFunction(source, destination, operation)
    }

    private fun blendFactor(factor: UlBlendFactor) = when (factor) {
        UlBlendFactor.ZERO -> BlendFactor.ZERO
        UlBlendFactor.ONE -> BlendFactor.ONE
        UlBlendFactor.SRC_COLOR -> BlendFactor.SRC_COLOR
        UlBlendFactor.INV_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR
        UlBlendFactor.SRC_ALPHA -> BlendFactor.SRC_ALPHA
        UlBlendFactor.INV_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA
        UlBlendFactor.DEST_COLOR -> BlendFactor.DST_COLOR
        UlBlendFactor.INV_DEST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR
        UlBlendFactor.DEST_ALPHA -> BlendFactor.DST_ALPHA
        UlBlendFactor.INV_DEST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA
        UlBlendFactor.SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE
    }

    private fun writeUniforms(encoder: CommandEncoder, state: UlGPUState, target: GpuTexture): GpuBufferSlice {
        val alignment = gpuDevice.deviceInfo.limits().minUniformOffsetAlignment().toLong()
        val memory = encoder.transientMemory().allocateGpuMapped(UNIFORMS_SIZE, alignment, GpuBuffer.USAGE_UNIFORM)

        memory.use {
            val builder = Std140Builder.intoBuffer(it.data())
                .putVec4(0f, state.viewportWidth().toFloat(), state.viewportHeight().toFloat(), 1f)
                .putMat4f(transform(state, target))

            for (i in 0 until UlGPUState.UNIFORM_INTEGERS step 4) {
                builder.putIVec4(
                    state.uniformInteger(i),
                    state.uniformInteger(i + 1),
                    state.uniformInteger(i + 2),
                    state.uniformInteger(i + 3)
                )
            }

            for (i in 0 until UlGPUState.UNIFORM_SCALARS step 4) {
                builder.putVec4(
                    state.uniformScalar(i),
                    state.uniformScalar(i + 1),
                    state.uniformScalar(i + 2),
                    state.uniformScalar(i + 3)
                )
            }

            for (i in 0 until UlGPUState.UNIFORM_VECTORS) {
                builder.putVec4(
                    state.uniformVector(i, 0),
                    state.uniformVector(i, 1),
                    state.uniformVector(i, 2),
                    state.uniformVector(i, 3)
                )
            }

            builder.putIVec4(state.clipSize(), 0, 0, 0)

            for (clip in 0 until UlGPUState.MAX_CLIPS) {
                for (i in 0 until 16) {
                    matrixValues[i] = state.clip(clip, i)
                }
                builder.putMat4f(transform.set(matrixValues))
            }
        }

        return memory.slice()
    }

    /**
     * Projects the page onto the render target, with its top in the first row of the texture.
     *
     * The render pass covers the whole texture, which starts at the origin of Ultralight's viewport, so the projection
     * works in texels of the texture instead.
     */
    private fun transform(state: UlGPUState, target: GpuTexture): Matrix4f {
        for (i in 0 until 16) {
            matrixValues[i] = state.transform(i)
        }

        val width = target.getWidth(0).toFloat()
        val height = target.getHeight(0).toFloat()

        // Depth is unused and stays at zero, which lies within the clip space of every backend
        projection.set(
            2f / width, 0f, 0f, 0f,
            0f, 2f / height, 0f, 0f,
            0f, 0f, 0f, 0f,
            -1f, -1f, 0f, 1f
        )
        return projection.mul(transform.set(matrixValues))
    }

    private fun renderTarget(state: UlGPUState): Texture? {
        val textureId = renderBufferTextures.get(state.renderBufferId())
        return textures[textureId]
    }

    private fun createTexture(bitmap: UltralightBitmap, isRenderTarget: Boolean): Texture {
        var usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
        if (isRenderTarget) {
            usage = usage or GpuTexture.USAGE_RENDER_ATTACHMENT
        }

        val gpuTexture = gpuDevice.createTexture(
            "Ultralight texture",
            usage,
            textureFormat(bitmap.format(), isRenderTarget),
            bitmap.width().toInt(),
            bitmap.height().toInt(),
            1,
            1
        )
        return Texture(gpuTexture, gpuDevice.createTextureView(gpuTexture), bitmap.format(), isRenderTarget)
    }

    private fun createEmptyTexture(format: GpuFormat, pixelSize: Int): Texture {
        val gpuTexture = gpuDevice.createTexture(
            "Ultralight empty texture",
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING,
            format,
            1,
            1,
            1,
            1
        )
        val encoder = gpuDevice.createCommandEncoder()
        val pixel = encoder.transientMemory()
            .uploadStaging(ByteBuffer.allocateDirect(pixelSize), pixelSize.toLong(), GpuBuffer.USAGE_COPY_SRC)
        encoder.copyBufferToTexture(pixel, 0, 0, 1, 1, gpuTexture, 0, 0, 1, 1, 0, 0)

        return Texture(gpuTexture, gpuDevice.createTextureView(gpuTexture), UlBitmapFormat.BGRA8_UNORM_SRGB, false)
    }

    /**
     * Uploads the changed part of a bitmap, which is how images, glyphs and Photon's data pages reach the GPU.
     */
    private fun upload(texture: Texture, bitmap: UltralightBitmap, rect: IntRect) {
        val format = bitmap.format()
        val bpp = format.bytesPerPixel()
        val rowBytes = bitmap.rowBytes().toInt()
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) {
            return
        }

        bitmap.lockPixels().use { pixels ->
            val source = pixels.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
            val encoder = gpuDevice.createCommandEncoder()

            when (format) {
                // Kept as RGBA, an A8 texture only has coverage in its alpha
                UlBitmapFormat.BGRA8_UNORM_SRGB, UlBitmapFormat.A8_UNORM -> {
                    val staging = encoder.transientMemory()
                        .allocateStaging(width * height * 4L, 4L, GpuBuffer.USAGE_COPY_SRC)
                    staging.use {
                        val target = it.data().order(ByteOrder.LITTLE_ENDIAN)
                        for (y in rect.top until rect.bottom) {
                            for (x in rect.left until rect.right) {
                                val offset = y * rowBytes + x * bpp
                                if (format == UlBitmapFormat.A8_UNORM) {
                                    target.putInt(source.get(offset).toInt() shl 24)
                                } else {
                                    // Swaps blue and red
                                    val bgra = source.getInt(offset)
                                    target.putInt(
                                        (bgra and 0xFF00FF00.toInt()) or ((bgra ushr 16) and 0xFF) or
                                            ((bgra and 0xFF) shl 16)
                                    )
                                }
                            }
                        }
                    }
                    encoder.copyBufferToTexture(
                        staging.slice(), 0, 0, width, height,
                        texture.texture, rect.left, rect.top, width, height, 0, 0
                    )
                }

                else -> {
                    val rows = source.slice(rect.top * rowBytes, (height - 1) * rowBytes + rect.right * bpp)
                    val staging = encoder.transientMemory()
                        .uploadStaging(rows, bpp.coerceAtLeast(4).toLong(), GpuBuffer.USAGE_COPY_SRC)
                    encoder.copyBufferToTexture(
                        staging, rect.left, 0, rowBytes / bpp, height,
                        texture.texture, rect.left, rect.top, width, height, 0, 0
                    )
                }
            }
        }
    }

    private fun write(buffer: GpuBuffer, label: Supplier<String>, usage: Int, data: ByteBuffer): GpuBuffer {
        if (data.remaining() <= buffer.size()) {
            gpuDevice.createCommandEncoder().writeToBuffer(buffer.slice(0, data.remaining().toLong()), data)
            return buffer
        }

        buffer.close()
        return gpuDevice.createBuffer(label, usage or GpuBuffer.USAGE_COPY_DST, data)
    }

    private fun textureFormat(format: UlBitmapFormat, isRenderTarget: Boolean) = when (format) {
        // Sampled from the red channel when it is a render target
        UlBitmapFormat.A8_UNORM -> if (isRenderTarget) GpuFormat.R8_UNORM else GpuFormat.RGBA8_UNORM
        UlBitmapFormat.RG8_UNORM -> GpuFormat.RG8_UNORM
        UlBitmapFormat.RGBA16F -> GpuFormat.RGBA16_FLOAT
        UlBitmapFormat.RGBA16UI -> GpuFormat.RGBA16_UINT
        UlBitmapFormat.RGBA32F -> GpuFormat.RGBA32_FLOAT
        else -> GpuFormat.RGBA8_UNORM
    }

    override fun close() {
        textures.values.forEach(Texture::close)
        textures.clear()
        geometries.values.forEach(Geometry::close)
        geometries.clear()
        renderBufferTextures.clear()

        emptyTexture.close()
        emptyIntegerTexture.close()
        dataPageSampler.close()
    }

    private inline fun guarded(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Reported once, it would repeat every frame
            if (!hasFailed) {
                hasFailed = true
                logger.error("Failed to $action for Ultralight", e)
            }
        }
    }

    companion object {
        private val DATA_PAGE_FORMATS = setOf(UlBitmapFormat.RGBA16F, UlBitmapFormat.RGBA16UI, UlBitmapFormat.RGBA32F)
    }

}
