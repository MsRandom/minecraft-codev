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
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.MinecraftType
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.Project
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream

internal fun Project.setupForgeRemapperIntegration() {
    plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
        val remapper = extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RemapperExtension::class.java)

        remapper.zipMappingsResolution { visitor, decorate, _, _ ->
            val configPath = getPath("config.json")
            if (configPath.exists()) {
                val mcpDependency = dependencies.create(configPath.inputStream().use { MinecraftCodevPlugin.json.decodeFromStream<UserdevConfig>(it) }.mcp)
                val mcp = unsafeResolveConfiguration(configurations.detachedConfiguration(mcpDependency).setTransitive(false)).singleFile

                MinecraftCodevPlugin.zipFileSystem(mcp.toPath()).use { mcpFs ->
                    val config = mcpFs.getPath("config.json").inputStream().decorate().use { MinecraftCodevPlugin.json.decodeFromStream<McpConfig>(it) }
                    val mappings = mcpFs.getPath(config.data.mappings)

                    val namespaceCompleter = MappingNsCompleter(visitor, mapOf(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE), true)

                    val classFixer = if (config.official) {
                        val clientMappingsDependency = extensions.getByType(MinecraftCodevExtension::class.java)(MinecraftType.ClientMappings, config.version)
                        val clientMappingsFile = unsafeResolveConfiguration(configurations.detachedConfiguration(clientMappingsDependency)).singleFile
                        val clientMappings = MemoryMappingTree()

                        clientMappingsFile.reader().use { ProGuardReader.read(it, clientMappings) }
                        ClassNameReplacer(namespaceCompleter, clientMappings, MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE, MappingUtil.NS_TARGET_FALLBACK)
                    } else {
                        namespaceCompleter
                    }

                    mappings.inputStream().reader().use {
                        TsrgReader.read(
                            it, MappingsNamespace.OBF, MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE, classFixer
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

                val sourceNamespace = existingMappings.getNamespaceId(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE)
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

private data class McpMapping(val name: String, val comment: String? = null)
