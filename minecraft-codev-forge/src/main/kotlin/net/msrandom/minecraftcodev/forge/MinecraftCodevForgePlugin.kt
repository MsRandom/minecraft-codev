package net.msrandom.minecraftcodev.forge

import de.siegmar.fastcsv.reader.NamedCsvReader
import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.applyPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.createSourceSetConfigurations
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftIvyDependencyDescriptorFactory
import net.msrandom.minecraftcodev.forge.resolve.PatchedMinecraftComponentResolvers
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.Plugin
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JvmEcosystemPlugin
import org.gradle.api.plugins.PluginAware
import java.io.File
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream

open class MinecraftCodevForgePlugin<T : PluginAware> : Plugin<T> {
    private fun applyGradle(gradle: Gradle) {
        MinecraftCodevPlugin.registerCustomDependency("patched", gradle, PatchedMinecraftIvyDependencyDescriptorFactory::class.java, PatchedMinecraftComponentResolvers::class.java)
    }

    override fun apply(target: T) {
        target.plugins.apply(MinecraftCodevPlugin::class.java)

        applyPlugin(target, ::applyGradle) {
            plugins.apply(JvmEcosystemPlugin::class.java)

            createSourceSetConfigurations(PATCHES_CONFIGURATION)

            dependencies.attributesSchema {
                it.attribute(FORGE_TRANSFORMED_ATTRIBUTE)
            }

            dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
                it.attributes.attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
            }

            configurations.all { configuration ->
                configuration.attributes {
                    it.attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
                }
            }

            @Suppress("UnstableApiUsage")
            dependencies.registerTransform(ForgeJarTransformer::class.java) {
                it.from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, false)
                it.to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(FORGE_TRANSFORMED_ATTRIBUTE, true)
            }

            plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
                val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

                remapper.zipMappingsResolution { visitor, decorate, _, _ ->
                    val configPath = getPath("config.json")
                    if (configPath.exists()) {
                        val mcpDependency = dependencies.create(configPath.inputStream().use { json.decodeFromStream<UserdevConfig>(it) }.mcp)
                        val mcp = unsafeResolveConfiguration(configurations.detachedConfiguration(mcpDependency).setTransitive(false)).singleFile

                        zipFileSystem(mcp.toPath()).use { mcpFs ->
                            val config = mcpFs.getPath("config.json").inputStream().decorate().use { json.decodeFromStream<McpConfig>(it) }
                            val mappings = mcpFs.getPath(config.data.mappings)

                            val namespaceCompleter = MappingNsCompleter(visitor, mapOf(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to SRG_MAPPINGS_NAMESPACE), true)

                            val classFixer = if (config.official) {
                                val clientMappingsDependency = extensions.getByType(MinecraftCodevExtension::class.java)(MinecraftType.ClientMappings, config.version)
                                val clientMappingsFile = unsafeResolveConfiguration(configurations.detachedConfiguration(clientMappingsDependency)).singleFile
                                val clientMappings = MemoryMappingTree()

                                clientMappingsFile.reader().use { ProGuardReader.read(it, clientMappings) }
                                ClassNameReplacer(namespaceCompleter, clientMappings, SRG_MAPPINGS_NAMESPACE, MappingUtil.NS_TARGET_FALLBACK)
                            } else {
                                namespaceCompleter
                            }

                            mappings.inputStream().reader().use {
                                TsrgReader.read(
                                    it, MappingsNamespace.OBF, SRG_MAPPINGS_NAMESPACE, classFixer
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
                        val targetNamespace = existingMappings.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

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

                remapper.extraFileRemapper { directory, sourceNamespaceId, targetNamespaceId ->
                    val path = directory.resolve("META-INF/accesstransformer.cfg")

                    if (path.exists()) {
                        val mappingSet = MappingSet.create()

                        for (treeClass in classes) {
                            val mapping = mappingSet.getOrCreateClassMapping(treeClass.getName(sourceNamespaceId))
                                .setDeobfuscatedName(treeClass.getName(targetNamespaceId))

                            for (field in treeClass.fields) {
                                val descriptor = field.getDesc(sourceNamespaceId)
                                val fieldMapping = if (descriptor == null) {
                                    mapping.getOrCreateFieldMapping(field.getName(sourceNamespaceId))
                                } else {
                                    mapping.getOrCreateFieldMapping(field.getName(sourceNamespaceId), descriptor)
                                }

                                fieldMapping.deobfuscatedName = field.getName(targetNamespaceId)
                            }

                            for (method in treeClass.methods) {
                                val methodMapping = mapping.getOrCreateMethodMapping(method.getName(sourceNamespaceId), method.getDesc(sourceNamespaceId))
                                    .setDeobfuscatedName(method.getName(targetNamespaceId))

                                for (argument in method.args) {
                                    methodMapping.getOrCreateParameterMapping(argument.argPosition).deobfuscatedName = argument.getName(targetNamespaceId)
                                }
                            }
                        }

                        val accessTransformer = AccessTransformFormats.FML.read(path)
                        path.deleteExisting()

                        AccessTransformFormats.FML.write(path, accessTransformer.remap(mappingSet))
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

        val FORGE_TRANSFORMED_ATTRIBUTE: Attribute<Boolean> = Attribute.of("net.msrandom.minecraftcodev.transformed", Boolean::class.javaObjectType)

        const val PATCHES_CONFIGURATION = "patches"

        internal fun userdevConfig(file: File, action: FileSystem.(config: UserdevConfig) -> Unit) = zipFileSystem(file.toPath()).use { fs ->
            val configPath = fs.getPath("config.json")
            if (configPath.exists()) {
                fs.action(configPath.inputStream().use(json::decodeFromStream))
                true
            } else {
                false
            }
        }
    }

    private data class McpMapping(val name: String, val comment: String? = null)
}
