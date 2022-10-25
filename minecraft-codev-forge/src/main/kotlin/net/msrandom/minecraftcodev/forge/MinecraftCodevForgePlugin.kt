package net.msrandom.minecraftcodev.forge

import de.siegmar.fastcsv.reader.NamedCsvReader
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.remapper.MappingNamespace
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.PluginAware
import org.gradle.kotlin.dsl.MinecraftType
import org.gradle.kotlin.dsl.minecraft
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun setupGradle(gradle: Gradle) {
        MinecraftCodevPlugin.registerCustomDependency("patched", gradle, PatchedMinecraftIvyDependencyDescriptorFactory::class.java, PatchedMinecraftComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        target.apply {
            it.plugin(MinecraftCodevPlugin::class.java)
        }

        if (target is Gradle) {
            setupGradle(target)
        } else if (target is Project) {
            setupGradle(target.gradle)

            target.configurations.create(PatcherExtension.PATCHES_CONFIGURATION) {
                it.isCanBeConsumed = false
                it.isTransitive = false
            }

            target.dependencies.attributesSchema { schema ->
                schema.attribute(PatcherExtension.PATCHED_ATTRIBUTE)
                schema.attribute(PatcherExtension.FORGE_TRANSFORMED_ATTRIBUTE)
            }

            target.dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                it.attributes.attribute(PatcherExtension.FORGE_TRANSFORMED_ATTRIBUTE, false)
            }

            target.configurations.all { configuration ->
                configuration.attributes {
                    it.attribute(PatcherExtension.FORGE_TRANSFORMED_ATTRIBUTE, true)
                }
            }

            target.dependencies.registerTransform(ForgeJarTransformer::class.java) {
                it.from.attribute(PatcherExtension.ARTIFACT_TYPE, ArtifactTypeDefinition.JAR_TYPE).attribute(PatcherExtension.FORGE_TRANSFORMED_ATTRIBUTE, false)
                it.to.attribute(PatcherExtension.ARTIFACT_TYPE, ArtifactTypeDefinition.JAR_TYPE).attribute(PatcherExtension.FORGE_TRANSFORMED_ATTRIBUTE, true)
            }

            target.plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
                val remapper = target.extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

                remapper.zipMappingsResolution { visitor, decorate, _, _ ->
                    val configPath = getPath("config.json")
                    if (configPath.exists()) {
                        val mcpDependency = target.dependencies.create(configPath.inputStream().use { json.decodeFromStream<UserdevConfig>(it) }.mcp.toString())
                        val mcp = target.unsafeResolveConfiguration(target.configurations.detachedConfiguration(mcpDependency).setTransitive(false)).singleFile

                        zipFileSystem(mcp.toPath()).use { mcpFs ->
                            val config = mcpFs.getPath("config.json").inputStream().decorate().use { json.decodeFromStream<McpConfig>(it) }
                            val mappings = mcpFs.getPath(config.data.mappings)

                            val namespaceCompleter = MappingNsCompleter(visitor, mapOf(MappingNamespace.NAMED to SRG_MAPPINGS_NAMESPACE), true)

                            val classFixer = if (config.official) {
                                val clientMappingsDependency = target.dependencies.minecraft(MinecraftType.ClientMappings, config.version)
                                val clientMappingsFile = target.unsafeResolveConfiguration(target.configurations.detachedConfiguration(clientMappingsDependency)).singleFile
                                val clientMappings = MemoryMappingTree()

                                clientMappingsFile.reader().use { ProGuardReader.read(it, clientMappings) }
                                ClassNameReplacer(namespaceCompleter, clientMappings, SRG_MAPPINGS_NAMESPACE, MappingUtil.NS_TARGET_FALLBACK)
                            } else {
                                namespaceCompleter
                            }

                            mappings.inputStream().reader().use {
                                TsrgReader.read(
                                    it, MappingNamespace.OBF, SRG_MAPPINGS_NAMESPACE, classFixer
                                )
                            }
                        }

                        true
                    } else {
                        false
                    }
                }

                remapper.zipMappingsResolution { visitor, decorate, existingMappings, _ ->
                    val methods = getPath("methods.csv")
                    val fields = getPath("fields.csv")

                    // We just need one of those to assume these are MCP mappings.
                    if (methods.exists() || fields.exists()) {
                        val params = getPath("params.csv")

                        val methodsMap = readMcp(methods, "searge", decorate)
                        val fieldsMap = readMcp(fields, "searge", decorate)
                        val paramsMap = readMcp(params, "param", decorate)

                        val sourceNamespace = existingMappings.getNamespaceId(SRG_MAPPINGS_NAMESPACE)
                        val targetNamespace = existingMappings.getNamespaceId(MappingNamespace.NAMED)

                        do {
                            if (visitor.visitContent()) {
                                for (classMapping in existingMappings.classes.toList()) {
                                    visitor.visitClass(classMapping.srcName)

                                    if (visitor.visitElementContent(MappedElementKind.CLASS)) {
                                        if (fieldsMap.isNotEmpty()) {
                                            for (field in classMapping.fields.toList()) {
                                                val name = field.getName(sourceNamespace)
                                                val mapping = fieldsMap[name]

                                                visitor.visitField(field.srcName, field.srcDesc)

                                                if (mapping != null) {
                                                    visitor.visitDstName(MappedElementKind.FIELD, targetNamespace, mapping.name)

                                                    if (mapping.comment != null) {
                                                        visitor.visitComment(MappedElementKind.FIELD, mapping.comment)
                                                    }
                                                }
                                            }
                                        }

                                        if (methodsMap.isNotEmpty() || paramsMap.isNotEmpty()) {
                                            for (method in classMapping.methods.toList()) {
                                                if (methodsMap.isNotEmpty()) {
                                                    val name = method.getName(sourceNamespace)
                                                    val mapping = methodsMap[name]

                                                    visitor.visitMethod(method.srcName, method.srcDesc)

                                                    if (mapping != null) {
                                                        visitor.visitDstName(MappedElementKind.METHOD, targetNamespace, mapping.name)

                                                        if (mapping.comment != null) {
                                                            visitor.visitComment(MappedElementKind.METHOD, mapping.comment)
                                                        }
                                                    }
                                                }

                                                if (paramsMap.isNotEmpty()) {
                                                    for (argument in method.args.toList()) {
                                                        val name = argument.getName(sourceNamespace)
                                                        val mapping = paramsMap[name]

                                                        visitor.visitMethodArg(argument.argPosition, argument.lvIndex, argument.srcName)

                                                        if (mapping != null) {
                                                            visitor.visitDstName(MappedElementKind.METHOD_ARG, targetNamespace, mapping.name)

                                                            if (mapping.comment != null) {
                                                                visitor.visitComment(MappedElementKind.METHOD_ARG, mapping.comment)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } while (!visitor.visitEnd())

                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun readMcp(path: Path, source: String, decorate: InputStream.() -> InputStream) =
        path.takeIf(Path::exists)?.inputStream()?.decorate()?.reader()?.let { NamedCsvReader.builder().build(it) }?.use {
            buildMap {
                if (it.header.contains("desc")) {
                    for (row in it.stream()) {
                        put(row.getField(source), McpMapping(row.getField("name"), row.getField("desc")))
                    }
                } else {
                    for (row in it.stream()) {
                        put(row.getField(source), McpMapping(row.getField("name")))
                    }
                }
            }
        } ?: emptyMap()

    companion object {
        const val SRG_MAPPINGS_NAMESPACE = "srg"
    }

    private data class McpMapping(val name: String, val comment: String? = null)
}
