package net.msrandom.minecraftcodev.fabric.mappings

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.accesswidener.AccessWidenerReader
import net.fabricmc.accesswidener.AccessWidenerWriter
import net.fabricmc.accesswidener.ForwardingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny1Reader
import net.fabricmc.mappingio.format.Tiny2Reader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin
import net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin.Companion.MOD_JSON
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.Project
import java.nio.file.Path
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.io.path.writeText

private fun readTiny(
    data: MappingResolutionData,
    path: Path,
) {
    data.decorate(path.inputStream()).bufferedReader().use { reader ->
        reader.mark(16)

        val parts = reader.readLine().split('\t')

        reader.reset()

        val version =
            when {
                parts[0].startsWith('v') -> parts[0].substring(1).toInt()
                parts[0] == "tiny" -> parts[1].toInt()
                else -> throw IllegalArgumentException("Invalid tiny header found")
            }

        val namespaceCompleter =
            if (MinecraftCodevFabricPlugin.INTERMEDIARY_MAPPINGS_NAMESPACE in parts && data.visitor.tree.getNamespaceId(
                    MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
                ) == MappingTreeView.NULL_NAMESPACE_ID
            ) {
                MappingNsCompleter(
                    data.visitor.tree,
                    mapOf(
                        MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to MinecraftCodevFabricPlugin.INTERMEDIARY_MAPPINGS_NAMESPACE,
                    ),
                    true,
                )
            } else {
                data.visitor.tree
            }

        val renamer = MappingNsRenamer(namespaceCompleter, mapOf("official" to MappingsNamespace.OBF))

        when (version) {
            1 -> Tiny1Reader.read(reader, renamer)
            2 -> Tiny2Reader.read(reader, renamer)
            else -> throw IllegalArgumentException("Unknown tiny mappings version found")
        }
    }
}

fun Project.setupFabricRemapperIntegration() {
    plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
        val remapper = extension<MinecraftCodevExtension>().extension<RemapperExtension>()

        remapper.mappingsResolution.add { path, extension, data ->
            if (extension == "tiny") {
                readTiny(data, path)
                true
            } else {
                false
            }
        }

        remapper.zipMappingsResolution.add { _, fileSystem, _, data ->
            val tiny = fileSystem.getPath("mappings/mappings.tiny")
            if (tiny.exists()) {
                // Assuming tiny
                readTiny(data, tiny)
                true
            } else {
                false
            }
        }

        remapper.extraFileRemappers.add { mappings, fileSystem, sourceNamespace, targetNamespace ->
            val modJson = fileSystem.getPath(MinecraftCodevFabricPlugin.MOD_JSON)
            if (modJson.notExists()) return@add

            val json =
                modJson.inputStream().use {
                    json.decodeFromStream<JsonObject>(it)
                }

            val accessWidener = json["accessWidener"]?.jsonPrimitive?.contentOrNull?.let(fileSystem::getPath) ?: return@add

            if (accessWidener.notExists()) {
                return@add
            }

            val writer = AccessWidenerWriter()

            val reader =
                AccessWidenerReader(
                    object : ForwardingVisitor(writer) {
                        private var sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
                        private val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

                        private fun String?.orNull() = if (this == null || this == "null") null else this

                        override fun visitHeader(namespace: String) {
                            sourceNamespaceId = mappings.getNamespaceId(namespace.takeUnless { it == "official" } ?: MappingsNamespace.OBF)

                            super.visitHeader(targetNamespace.takeUnless { it == MappingsNamespace.OBF } ?: "official")
                        }

                        override fun visitClass(
                            name: String,
                            access: AccessWidenerReader.AccessType,
                            transitive: Boolean,
                        ) {
                            val remapped = mappings.getClass(name, sourceNamespaceId)?.getName(targetNamespaceId).orNull()

                            super.visitClass(remapped ?: name, access, transitive)
                        }

                        override fun visitMethod(
                            owner: String,
                            name: String,
                            descriptor: String,
                            access: AccessWidenerReader.AccessType,
                            transitive: Boolean,
                        ) {
                            val classView = mappings.getClass(owner, sourceNamespaceId)
                            val method = classView?.getMethod(name, descriptor, sourceNamespaceId)

                            val remappedClass = classView?.getName(targetNamespaceId).orNull()
                            val remapped = method?.getName(targetNamespaceId).orNull()
                            val remappedDesc = method?.getDesc(targetNamespaceId).orNull()

                            super.visitMethod(remappedClass ?: owner, remapped ?: name, remappedDesc ?: descriptor, access, transitive)
                        }

                        override fun visitField(
                            owner: String,
                            name: String,
                            descriptor: String,
                            access: AccessWidenerReader.AccessType,
                            transitive: Boolean,
                        ) {
                            val classView = mappings.getClass(owner, sourceNamespaceId)
                            val field = classView?.getField(name, descriptor, sourceNamespaceId)
                            val remappedClass = classView?.getName(targetNamespaceId).orNull()
                            val remapped = field?.getName(targetNamespaceId).orNull()
                            val remappedDesc = field?.getDesc(targetNamespaceId).orNull()

                            super.visitField(remappedClass ?: owner, remapped ?: name, remappedDesc ?: descriptor, access, transitive)
                        }
                    },
                )

            accessWidener.inputStream().bufferedReader().use {
                reader.read(it, sourceNamespace)
            }

            accessWidener.writeText(writer.writeString())
        }

        remapper.modDetectionRules.add {
            it.getPath(MOD_JSON).exists()
        }

        remapper.modDetectionRules.add {
            val path = it.getPath("META-INF", "MANIFEST.MF")

            path.exists() && path.inputStream().use(::Manifest).mainAttributes.keys.any { it.toString().startsWith("Fabric") }
        }
    }
}
