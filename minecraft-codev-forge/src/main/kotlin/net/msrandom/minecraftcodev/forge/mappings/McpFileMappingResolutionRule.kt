package net.msrandom.minecraftcodev.forge.mappings

import de.siegmar.fastcsv.reader.NamedCsvReader
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.adapter.ForwardingMappingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.SRG_MAPPINGS_NAMESPACE
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.ZipMappingResolutionRule
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

class McpFileMappingResolutionRule : ZipMappingResolutionRule {
    private fun readMcp(
        path: Path,
        source: String,
    ) = path.takeIf(Path::exists)?.inputStream()?.reader()?.let { NamedCsvReader.builder().build(it) }?.use {
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

    override suspend fun load(
        path: Path,
        fileSystem: FileSystem,
        isJar: Boolean,
        data: MappingResolutionData,
    ): Boolean {
        val methods = fileSystem.getPath("methods.csv")
        val fields = fileSystem.getPath("fields.csv")

        // We just need one of those to assume these are MCP mappings.
        if (!methods.exists() && !fields.exists()) {
            return false
        }

        val params = fileSystem.getPath("params.csv")

        val methodsMap = readMcp(methods, "searge")
        val fieldsMap = readMcp(fields, "searge")
        val paramsMap = readMcp(params, "param")

        data.visitor.withTree(SRG_MAPPINGS_NAMESPACE) { tree ->
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

        return true
    }

    private data class McpMapping(
        val name: String,
        val comment: String? = null,
    )
}
