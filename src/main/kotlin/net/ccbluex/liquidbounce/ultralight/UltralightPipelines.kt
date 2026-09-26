package net.ccbluex.liquidbounce.ultralight

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.ccbluex.liquidbounce.render.utils.LiteralShaderSource
import net.ccbluex.liquidbounce.utils.client.gpuDevice
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.util.Optional

private const val NAMESPACE = "liquidbounce-ultralight"

/**
 * The pipelines Ultralight's pages are drawn with, compiled by the add-on itself from its own shaders.
 */
internal object UltralightPipelines {

    /**
     * Ultralight's fill vertices, `2f_4ub_2f_2f_28f`: position, color, texture and object coordinates, then seven
     * vectors of data depending on the fill type.
     */
    private val fillFormat = VertexFormat.builder(0)
        .addAttribute("in_Position", GpuFormat.RG32_FLOAT)
        .addAttribute("in_Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("in_TexCoord", GpuFormat.RG32_FLOAT)
        .addAttribute("in_ObjCoord", GpuFormat.RG32_FLOAT)
        .apply {
            repeat(7) { addAttribute("in_Data$it", GpuFormat.RGBA32_FLOAT) }
        }
        .build()

    /**
     * Ultralight's path vertices, `2f_4ub_2f`: position, color and object coordinates.
     */
    private val fillPathFormat = VertexFormat.builder(0)
        .addAttribute("in_Position", GpuFormat.RG32_FLOAT)
        .addAttribute("in_Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("in_TexCoord", GpuFormat.RG32_FLOAT)
        .build()

    private val shaders = mutableMapOf<Identifier, String>()
    private val shaderSource = LiteralShaderSource(shaders, useFallback = false)

    lateinit var fill: CompiledRenderPipeline
        private set
    lateinit var fillBlended: CompiledRenderPipeline
        private set
    lateinit var fillPath: CompiledRenderPipeline
        private set
    lateinit var fillPathBlended: CompiledRenderPipeline
        private set

    /**
     * Compiles the pipelines, on the render thread before the first page is drawn.
     */
    fun compile() {
        fill = compile("fill", fillFormat, blend = false, textures = true)
        fillBlended = compile("fill", fillFormat, blend = true, textures = true)
        fillPath = compile("fill_path", fillPathFormat, blend = false, textures = false)
        fillPathBlended = compile("fill_path", fillPathFormat, blend = true, textures = false)
    }

    private fun compile(shader: String, format: VertexFormat, blend: Boolean, textures: Boolean): CompiledRenderPipeline {
        val name = if (blend) "${shader}_blended" else shader
        val pipeline = RenderPipeline.Builder()
            .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/$name"))
            .withVertexShader(shader(shader, "vert"))
            .withFragmentShader(shader(shader, "frag"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withUniform("UltralightState", UniformType.UNIFORM_BUFFER)
                    .apply {
                        if (textures) {
                            withUniform("Texture1", UniformType.COMBINED_IMAGE_SAMPLER)
                            withUniform("Texture2", UniformType.COMBINED_IMAGE_SAMPLER)
                        }
                    }
                    .build()
            )
            .withVertexBinding(0, format)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(
                if (blend) ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA) else ColorTargetState.DEFAULT
            )
            .build()

        return gpuDevice.compilePipeline(pipeline, shaderSource, Util.backgroundExecutor()).join().finishCompile()
            ?: error("Failed to compile the Ultralight pipeline $name")
    }

    /**
     * Loads a shader of the add-on, see `assets/liquidbounce-ultralight/shaders`.
     */
    private fun shader(name: String, extension: String): Identifier {
        val id = Identifier.fromNamespaceAndPath(NAMESPACE, "shader/$name.$extension")
        shaders.getOrPut(id) {
            val path = "/assets/$NAMESPACE/shaders/$name.$extension"
            val stream = checkNotNull(UltralightPipelines::class.java.getResourceAsStream(path)) { "Missing $path" }
            stream.use { it.readAllBytes().decodeToString() }
        }
        return id
    }

}
