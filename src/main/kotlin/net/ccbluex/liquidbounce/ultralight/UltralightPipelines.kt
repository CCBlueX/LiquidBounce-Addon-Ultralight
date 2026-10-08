package net.ccbluex.liquidbounce.ultralight

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.BlendOp
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.ccbluex.liquidbounce.render.utils.LiteralShaderSource
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.janrupf.ujr.api.gpu.UlShaderType
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.io.File
import java.util.Optional

private const val NAMESPACE = "liquidbounce-ultralight"

/**
 * The uniform block of every shader of Ultralight.
 */
internal const val UNIFORMS = "type_Uniforms"

/**
 * The pipelines Ultralight's pages are drawn with, from the shaders of the Ultralight SDK.
 *
 * The shaders are used as they come, so the bindings keep the names SPIRV-Cross gave them.
 */
internal object UltralightPipelines {

    /**
     * A shader program of Ultralight and the GPU state slot, 1 to 4, each of its textures is bound from.
     */
    class Program(val vertex: String, val fragment: String, val format: VertexFormat, val textures: Map<String, Int>)

    private data class Key(val shader: UlShaderType, val target: GpuFormat, val blend: BlendFunction?)

    /**
     * `2f_4ub_2f_2f_28f`: position, color, texture and object coordinates, then seven vectors of data.
     */
    private val quadFormat = VertexFormat.builder(0)
        .addAttribute("in_var_POSITION", GpuFormat.RG32_FLOAT)
        .addAttribute("in_var_COLOR0", GpuFormat.RGBA8_UNORM)
        .addAttribute("in_var_TEXCOORD0", GpuFormat.RG32_FLOAT)
        .addAttribute("in_var_TEXCOORD1", GpuFormat.RG32_FLOAT)
        .apply {
            for (i in 1..7) addAttribute("in_var_COLOR$i", GpuFormat.RGBA32_FLOAT)
        }
        .build()

    /**
     * `2f_4ub_2f`: position, color and object coordinates.
     */
    private val pathFormat = VertexFormat.builder(0)
        .addAttribute("in_var_POSITION", GpuFormat.RG32_FLOAT)
        .addAttribute("in_var_COLOR0", GpuFormat.RGBA8_UNORM)
        .addAttribute("in_var_TEXCOORD0", GpuFormat.RG32_FLOAT)
        .build()

    /**
     * `2f_2ui`: position, the address of the cell header in the index page and the slot of the path.
     */
    private val photonGridFormat = VertexFormat.builder(0)
        .addAttribute("in_var_POSITION", GpuFormat.RG32_FLOAT)
        .addAttribute("in_var_TEXCOORD0", GpuFormat.R32_UINT)
        .addAttribute("in_var_TEXCOORD1", GpuFormat.R32_UINT)
        .build()

    private const val TEXTURE_1 = "SPIRV_Cross_CombinedTexture0Sampler0"
    private const val TEXTURE_2 = "SPIRV_Cross_CombinedTexture1Sampler0"
    private const val TEXTURE_3 = "SPIRV_Cross_CombinedTexture2Sampler0"

    // The data pages of Photon, which are only read with texel fetches
    private val photonPages = mapOf(
        "SPIRV_Cross_CombinedTexture0SPIRV_Cross_DummySampler" to 1,
        "SPIRV_Cross_CombinedTexture2SPIRV_Cross_DummySampler" to 3,
        "SPIRV_Cross_CombinedIndexTextureSPIRV_Cross_DummySampler" to 4
    )

    val programs = mapOf(
        UlShaderType.FILL to
            Program("vertex_quad_vs", "fill_fs", quadFormat, mapOf(TEXTURE_1 to 1, TEXTURE_2 to 2, TEXTURE_3 to 3)),
        UlShaderType.FILL_PATH to Program("vertex_path_vs", "fill_path_fs", pathFormat, emptyMap()),
        UlShaderType.FILTER_BASIC to
            Program("vertex_quad_vs", "filter_basic_fs", quadFormat, mapOf(TEXTURE_1 to 1, TEXTURE_2 to 2)),
        UlShaderType.FILTER_BLUR to Program("vertex_quad_vs", "filter_blur_fs", quadFormat, mapOf(TEXTURE_1 to 1)),
        UlShaderType.FILTER_DROP_SHADOW to
            Program("vertex_quad_vs", "filter_dropshadow_fs", quadFormat, mapOf(TEXTURE_1 to 1, TEXTURE_2 to 2)),
        UlShaderType.FILL_PHOTON to Program("vertex_quad_vs", "fill_photon_fs", quadFormat, photonPages),
        UlShaderType.FILL_PHOTON_GRID to
            Program("vertex_photon_grid_vs", "fill_photon_grid_fs", photonGridFormat, photonPages)
    )

    /**
     * How Ultralight blends premultiplied colors, what nearly every draw uses.
     */
    val premultiplied = BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD)

    private val shaders = mutableMapOf<Identifier, String>()
    private val shaderSource = LiteralShaderSource(shaders, useFallback = false)
    private val pipelines = HashMap<Key, CompiledRenderPipeline>()

    /**
     * Reads the shaders from the SDK and compiles the pipelines most draws use, on the render thread before the
     * first page is drawn.
     */
    fun load(shaderDirectory: File) {
        pipelines.clear()
        shaders.clear()

        for (program in programs.values) {
            for (name in listOf(program.vertex, program.fragment)) {
                shaders.getOrPut(id(name)) { readShader(shaderDirectory.resolve("$name.h")) }
            }
        }

        // Compiled side by side, Photon's shaders take a while
        val pending = programs.keys.flatMap { shader ->
            listOf(null, premultiplied).map { blend ->
                val key = Key(shader, GpuFormat.RGBA8_UNORM, blend)
                key to gpuDevice.compilePipeline(build(key), shaderSource, Util.backgroundExecutor())
            }
        }

        for ((key, future) in pending) {
            pipelines[key] = finish(key, future.join())
        }
    }

    /**
     * Finds the pipeline of a shader for a render target, compiling it the first time it is used.
     *
     * @param blend how to blend, or null to overwrite
     */
    fun get(shader: UlShaderType, target: GpuFormat, blend: BlendFunction?): CompiledRenderPipeline {
        val key = Key(shader, target, blend)
        return pipelines.getOrPut(key) {
            finish(key, gpuDevice.compilePipeline(build(key), shaderSource, Util.backgroundExecutor()).join())
        }
    }

    private fun build(key: Key): RenderPipeline {
        val program = programs.getValue(key.shader)
        val blend = when (key.blend) {
            null -> "opaque"
            premultiplied -> "premultiplied"
            else -> "blend_${key.blend.hashCode().toUInt()}"
        }
        val name = "${key.shader.name.lowercase()}/${key.target.name.lowercase()}/$blend"

        return RenderPipeline.Builder()
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/$name"))
            .withVertexShader(id(program.vertex))
            .withFragmentShader(id(program.fragment))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform(UNIFORMS, UniformType.UNIFORM_BUFFER)
                    .apply {
                        program.textures.keys.forEach { withUniform(it, UniformType.COMBINED_IMAGE_SAMPLER) }
                    }
                    .build()
            )
            .withVertexBinding(0, program.format)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.ofNullable(key.blend), key.target, ColorTargetState.WRITE_ALL))
            .build()
    }

    private fun finish(key: Key, pending: CompiledRenderPipeline.Pending) =
        pending.finishCompile() ?: error("Failed to compile the Ultralight pipeline for $key")

    private fun id(name: String) = Identifier.fromNamespaceAndPath(NAMESPACE, "shader/$name")

    /**
     * Takes the GLSL out of a shader header of the SDK, where it is a raw string literal.
     */
    private fun readShader(header: File): String {
        val text = header.readText()
        val start = text.indexOf(SOURCE_START)
        val end = text.lastIndexOf(SOURCE_END)
        check(start >= 0 && end > start) { "${header.name} holds no GLSL" }
        return text.substring(start + SOURCE_START.length, end)
    }

    private const val SOURCE_START = "R\"GLSL("
    private const val SOURCE_END = ")GLSL\""

}
