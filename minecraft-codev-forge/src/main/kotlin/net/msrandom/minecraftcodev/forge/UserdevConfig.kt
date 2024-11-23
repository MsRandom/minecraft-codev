package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.inputStream

object AtsSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("AccessTransformers")

    override fun deserialize(decoder: Decoder): List<String> {
        decoder as JsonDecoder

        return when (val element = decoder.decodeJsonElement()) {
            is JsonArray -> element.map { (it as JsonPrimitive).content }
            is JsonPrimitive -> listOf(element.content)
            else -> throw IllegalStateException("$.ats is expected to be a String or an array")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: List<String>,
    ) {
        encoder as JsonEncoder

        encoder.encodeJsonElement(JsonArray(value.map(::JsonPrimitive)))
    }
}

@Serializable
data class UserdevConfig(
    val notchObf: Boolean = false,
    val universalFilters: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val mcp: String,
    @Serializable(AtsSerializer::class) val ats: List<String>,
    val binpatches: String,
    val binpatcher: PatchLibrary,
    val sources: String,
    val universal: String,
    val libraries: List<String>,
    val inject: String? = null,
    val runs: Runs,
    val spec: Int,
) {
    @Serializable
    data class Runs(
        val server: Run,
        val client: Run,
        val data: Run? = null,
        val gameTestServer: Run? = null,
    )

    @Serializable
    data class Run(
        val main: String,
        val args: List<String> = emptyList(),
        val jvmArgs: List<String> = emptyList(),
        val client: Boolean? = null,
        val env: Map<String, String>,
        val props: Map<String, String> = emptyMap(),
    )
}

data class Userdev(val config: UserdevConfig, val source: File) {
    private sealed interface CacheEntry {
        val value: UserdevConfig?

        object Absent : CacheEntry {
            override val value: UserdevConfig? = null
        }

        @JvmInline
        value class Present(override val value: UserdevConfig) : CacheEntry
    }

    companion object {
        private val cache = ConcurrentHashMap<File, CacheEntry>()

        fun fromFile(file: File) =
            cache.computeIfAbsent(file) {
                println("Loading $it as userdev")

                zipFileSystem(it.toPath()).use { fs ->
                    fs.getPath("config.json")
                        .takeIf(Path::exists)
                        ?.inputStream()
                        ?.use { json.decodeFromStream<UserdevConfig>(it) }
                        ?.let(CacheEntry::Present)
                        ?: CacheEntry.Absent
                }
            }.value?.let { Userdev(it, file) }
    }
}
