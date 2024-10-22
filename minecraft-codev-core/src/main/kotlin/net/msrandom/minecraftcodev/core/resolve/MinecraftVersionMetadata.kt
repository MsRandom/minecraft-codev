package net.msrandom.minecraftcodev.core.resolve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import net.msrandom.minecraftcodev.core.URISerializer
import net.msrandom.minecraftcodev.core.utils.extension
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.net.URI

@Serializable
data class MinecraftVersionMetadata(
    val arguments: Arguments = Arguments(emptyList(), emptyList()),
    val assetIndex: AssetIndex,
    val assets: String,
    val downloads: Map<String, Download>,
    val id: String,
    val javaVersion: JavaVersionData = JavaVersionData(8),
    val libraries: List<Library>,
    val type: String,
    val mainClass: String,
    val minecraftArguments: String = "",
) {
    @Serializable
    data class Arguments(
        @Serializable(ArgumentListSerializer::class)
        val game: List<Argument>,
        @Serializable(ArgumentListSerializer::class)
        val jvm: List<Argument>,
    )

    @Serializable
    data class Argument(val rules: List<Rule> = emptyList(), val value: List<String>)

    @Serializable
    data class Rule(val action: RuleAction, val features: Map<String, Boolean> = emptyMap(), val os: OperatingSystem? = null) {
        @Serializable
        data class OperatingSystem(val name: String? = null, val version: String? = null, val arch: String? = null)
    }

    @Serializable
    enum class RuleAction {
        @SerialName("allow")
        Allow,

        @SerialName("disallow")
        Disallow,
    }

    @Serializable
    data class AssetIndex(
        val id: String,
        val sha1: String,
        val size: Long,
        val totalSize: Long,
        @Serializable(URISerializer::class) val url: URI,
    )

    @Serializable
    data class Download(
        val path: String = "",
        val sha1: String,
        val size: Long,
        @Serializable(URISerializer::class) val url: URI,
    )

    @Serializable
    data class JavaVersionData(val majorVersion: Int) {
        fun executable(toolchainService: JavaToolchainService): Provider<RegularFile> = toolchainService
            .launcherFor { it.languageVersion.set(JavaLanguageVersion.of(majorVersion)) }
            .map { it.executablePath }

        fun executable(project: Project) =
            executable(project.extension<JavaToolchainService>())
    }

    @Serializable
    data class Library(
        val downloads: LibraryDownloads,
        val extract: ExtractData? = null,
        val name: String,
        val natives: Map<String, String> = emptyMap(),
        val rules: List<Rule> = emptyList(),
    ) {
        @Serializable
        data class LibraryDownloads(val artifact: Download? = null, val classifiers: Map<String, Download> = emptyMap())

        @Serializable
        data class ExtractData(val exclude: List<String>)
    }

    class ArgumentListSerializer : JsonTransformingSerializer<List<Argument>>(ListSerializer(Argument.serializer())) {
        override fun transformDeserialize(element: JsonElement) =
            if (element is JsonArray) {
                JsonArray(
                    element.map {
                        if (it is JsonPrimitive) {
                            JsonObject(
                                mapOf("value" to JsonArray(listOf(it))),
                            )
                        } else {
                            val rules = it.jsonObject["rules"]
                            val value = it.jsonObject["value"]
                            if (rules is JsonArray && value is JsonPrimitive) {
                                JsonObject(
                                    mapOf(
                                        "rules" to rules,
                                        "value" to JsonArray(listOf(value)),
                                    ),
                                )
                            } else {
                                it
                            }
                        }
                    },
                )
            } else {
                element
            }
    }
}
