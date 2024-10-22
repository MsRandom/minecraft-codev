@file:UseSerializers(EitherSerializer::class)

package net.msrandom.minecraftcodev.forge

import arrow.core.serialization.EitherSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonObject
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Serializer(String::class)
class DataSerializer : KSerializer<Map<String, String?>> {
    override fun deserialize(decoder: Decoder): Map<String, String?> {
        decoder as JsonDecoder

        return decoder.decodeJsonElement().jsonObject.mapValues { (_, value) ->
            if (value is JsonPrimitive) {
                value.content
            } else {
                null
            }
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Map<String, String?>,
    ) {
        throw UnsupportedOperationException("Can not serialize")
    }
}

@Serializable
data class McpConfig(
    val version: String,
    val official: Boolean = false,
    val data:
    @Serializable(DataSerializer::class)
    Map<String, String?>,
    val functions: Map<String, PatchLibrary>,
)

data class McpConfigFile(
    val config: McpConfig,
    val source: Path,
) {
    companion object {
        private val cache = hashMapOf<Path, McpConfig?>()

        fun fromFile(file: File) =
            cache.computeIfAbsent(file.toPath()) {
                zipFileSystem(it).use { fs ->
                    fs.base.getPath("config.json")
                        .takeIf(Path::exists)
                        ?.inputStream()
                        ?.use(json::decodeFromStream)
                }
            }?.let { McpConfigFile(it, file.toPath()) }

        fun fromFile(file: Path, fileSystem: FileSystem) =
            cache.computeIfAbsent(file) {
                    fileSystem.getPath("config.json")
                        .takeIf(Path::exists)
                        ?.inputStream()
                        ?.use(json::decodeFromStream)
            }?.let { McpConfigFile(it, file) }
    }
}
