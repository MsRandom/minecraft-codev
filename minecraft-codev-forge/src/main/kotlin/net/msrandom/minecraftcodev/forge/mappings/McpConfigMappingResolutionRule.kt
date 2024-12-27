package net.msrandom.minecraftcodev.forge.mappings

import com.google.common.io.ByteStreams.nullOutputStream
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.SRG_MAPPINGS_NAMESPACE
import net.msrandom.minecraftcodev.forge.task.McpAction
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.ZipMappingResolutionRule
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.inputStream

class McpConfigMappingResolutionRule : ZipMappingResolutionRule {
    override fun load(
        path: Path,
        fileSystem: FileSystem,
        isJar: Boolean,
        data: MappingResolutionData,
    ): Boolean {
        val mcpConfigFile = McpConfigFile.fromFile(path, fileSystem) ?: return false

        val mappings =
            if (mcpConfigFile.config.official) {
                val function = mcpConfigFile.config.functions.getValue("mergeMappings")

                val list = getVersionList(data.cacheDirectory, data.versionManifestUrl, data.isOffline)
                val version = list.version(mcpConfigFile.config.version)

                val javaExecutable = data.javaExecutable.asFile
                val clientMappings = downloadMinecraftFile(data.cacheDirectory, version, MinecraftDownloadVariant.ClientMappings, data.isOffline)!!.toFile()

                val mergeMappings =
                    McpAction(
                        data.execOperations,
                        javaExecutable,
                        data.collection,
                        function,
                        mcpConfigFile,
                        mapOf(
                            "official" to clientMappings,
                        ),
                        nullOutputStream(),
                    )

                mergeMappings.execute(fileSystem)
            } else {
                fileSystem.getPath(mcpConfigFile.config.data.getValue("mappings")!!)
            }

        val namedNamespace = data.visitor.tree.getNamespaceId(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)

        val namespaceCompleter =
            if (namedNamespace == MappingTreeView.NULL_NAMESPACE_ID) {
                MappingNsCompleter(
                    data.visitor.tree,
                    mapOf(
                        MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE to SRG_MAPPINGS_NAMESPACE,
                    ),
                    true,
                )
            } else {
                data.visitor.tree
            }

        val namespaceFixer =
            if (mcpConfigFile.config.official) {
                MappingNsRenamer(
                    namespaceCompleter,
                    mapOf("left" to MappingsNamespace.OBF, "right" to SRG_MAPPINGS_NAMESPACE),
                )
            } else {
                namespaceCompleter
            }

        mappings.inputStream().reader().use {
            TsrgReader.read(
                it,
                MappingsNamespace.OBF,
                SRG_MAPPINGS_NAMESPACE,
                namespaceFixer,
            )
        }

        return true
    }
}
