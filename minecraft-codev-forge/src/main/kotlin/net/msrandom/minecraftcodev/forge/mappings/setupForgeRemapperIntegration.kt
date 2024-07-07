package net.msrandom.minecraftcodev.forge.mappings

import de.siegmar.fastcsv.reader.NamedCsvReader
import kotlinx.coroutines.runBlocking
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingUtil
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.accesswidener.findAccessTransformers
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId
import org.cadixdev.at.io.AccessTransformFormats
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.Project
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.reader

internal fun Project.setupForgeRemapperIntegration() {
    plugins.withType(MinecraftCodevRemapperPlugin::class.java) {
        val remapper = extension<MinecraftCodevExtension>().extension<RemapperExtension>()

        remapper.zipMappingsResolution.add { path, _, _, data ->
            val userdev = Userdev.fromFile(path.toFile())?.config ?: return@add false

            val mcp = configurations.detachedConfiguration(dependencies.create(userdev.mcp)).singleFile

            val mcpConfigFile = McpConfigFile.fromFile(mcp) ?: return@add false

            val config = mcpConfigFile.config

            zipFileSystem(mcpConfigFile.source.toPath()).use { (mcpFs) ->
                val mappings = mcpFs.getPath(config.data.getValue("mappings")!!)

                val namespaceCompleter =
                    MappingNsCompleter(
                        data.visitor.tree,
                        mapOf(
                            MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                        ),
                        true,
                    )

                val classFixer =
                    if (config.official) {
                        runBlocking {
                            val version = extension<MinecraftCodevExtension>().getVersionList().version(config.version)
                            val artifactResult = downloadMinecraftFile(project, version, MinecraftDownloadVariant.ClientMappings)!!

                            val clientMappings = MemoryMappingTree()

                            artifactResult.reader().use { ProGuardReader.read(it, clientMappings) }
                            ClassNameReplacer(
                                namespaceCompleter,
                                clientMappings,
                                MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                                MappingUtil.NS_SOURCE_FALLBACK,
                                MappingUtil.NS_TARGET_FALLBACK,
                            )
                        }
                    } else {
                        namespaceCompleter
                    }

                mappings.inputStream().reader().use {
                    TsrgReader.read(
                        it,
                        MappingsNamespace.OBF,
                        MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                        classFixer,
                    )
                }
            }

            true
        }

        remapper.zipMappingsResolution.add { _, fileSystem, _, data ->
            val methods = fileSystem.getPath("methods.csv")
            val fields = fileSystem.getPath("fields.csv")

            // We just need one of those to assume these are MCP mappings.
            if (!methods.exists() && !fields.exists()) {
                return@add false
            }
            val params = fileSystem.getPath("params.csv")

            val methodsMap = readMcp(methods, "searge", data)
            val fieldsMap = readMcp(fields, "searge", data)
            val paramsMap = readMcp(params, "param", data)

            data.visitor.withTree(MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE) { tree ->
                tree.accept(
                    object : ForwardingMappingVisitor(data.visitor.tree) {
                        private var targetNamespace = MappingTreeView.NULL_NAMESPACE_ID

                        override fun visitNamespaces(
                            srcNamespace: String,
                            dstNamespaces: List<String>,
                        ) {
                            super.visitNamespaces(srcNamespace, dstNamespaces)

                            targetNamespace =
                                MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE.getNamespaceId(
                                    srcNamespace,
                                    dstNamespaces,
                                )
                        }

                        override fun visitClass(srcName: String) = true

                        override fun visitField(
                            srcName: String,
                            srcDesc: String?,
                        ): Boolean {
                            if (!super.visitField(srcName, srcDesc)) {
                                return false
                            }

                            fieldsMap[srcName]?.let { fieldMapping ->
                                visitDstName(MappedElementKind.FIELD, targetNamespace, fieldMapping.name)

                                fieldMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.FIELD, it)
                                }
                            }

                            return true
                        }

                        override fun visitMethod(
                            srcName: String,
                            srcDesc: String,
                        ): Boolean {
                            if (!super.visitMethod(srcName, srcDesc)) {
                                return false
                            }

                            methodsMap[srcName]?.let { methodMapping ->
                                visitDstName(MappedElementKind.METHOD, targetNamespace, methodMapping.name)

                                methodMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.METHOD, it)
                                }
                            }

                            return true
                        }

                        override fun visitMethodArg(
                            argPosition: Int,
                            lvIndex: Int,
                            srcName: String?,
                        ): Boolean {
                            if (!super.visitMethodArg(argPosition, lvIndex, srcName)) {
                                return false
                            }

                            srcName?.let(paramsMap::get)?.let { paramMapping ->
                                visitDstName(MappedElementKind.METHOD_ARG, targetNamespace, paramMapping.name)

                                paramMapping.comment?.let { it ->
                                    visitComment(MappedElementKind.METHOD_ARG, it)
                                }
                            }

                            return true
                        }
                    },
                )
            }

            true
        }

        remapper.extraFileRemappers.add { mappings, fileSystem, sourceNamespace, targetNamespace ->
            val sourceNamespaceId = mappings.getNamespaceId(sourceNamespace)
            val targetNamespaceId = mappings.getNamespaceId(targetNamespace)

            for (path in fileSystem.findAccessTransformers()) {
                val mappingSet = MappingSet.create()

                for (treeClass in mappings.classes) {
                    val className = treeClass.getName(sourceNamespaceId) ?: continue
                    val mapping = mappingSet.getOrCreateClassMapping(className)

                    treeClass.getName(targetNamespaceId)?.let {
                        mapping.deobfuscatedName = it
                    }

                    for (field in treeClass.fields) {
                        val name = field.getName(sourceNamespaceId) ?: continue
                        val descriptor = field.getDesc(sourceNamespaceId)

                        val fieldMapping =
                            if (descriptor == null) {
                                mapping.getOrCreateFieldMapping(name)
                            } else {
                                mapping.getOrCreateFieldMapping(name, descriptor)
                            }

                        fieldMapping.deobfuscatedName = field.getName(targetNamespaceId)
                    }

                    for (method in treeClass.methods) {
                        val name = method.getName(sourceNamespaceId) ?: continue
                        val methodMapping = mapping.getOrCreateMethodMapping(name, method.getDesc(sourceNamespaceId))

                        method.getName(targetNamespaceId)?.let {
                            methodMapping.deobfuscatedName = it
                        }

                        for (argument in method.args) {
                            methodMapping.getOrCreateParameterMapping(
                                argument.argPosition,
                            ).deobfuscatedName = argument.getName(targetNamespaceId)
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

private fun readMcp(
    path: Path,
    source: String,
    data: MappingResolutionData,
) = path.takeIf(Path::exists)?.inputStream()?.let(data::decorate)?.reader()?.let { NamedCsvReader.builder().build(it) }?.use {
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
