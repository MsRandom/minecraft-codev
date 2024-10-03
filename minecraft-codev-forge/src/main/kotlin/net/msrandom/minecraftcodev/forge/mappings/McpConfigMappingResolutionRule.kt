package net.msrandom.minecraftcodev.forge.mappings

import com.google.common.io.ByteStreams.nullOutputStream
import kotlinx.coroutines.runBlocking
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.lazyProvider
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.SRG_MAPPINGS_NAMESPACE
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.task.McpAction
import net.msrandom.minecraftcodev.forge.task.resolveFile
import net.msrandom.minecraftcodev.remapper.*
import net.msrandom.minecraftcodev.remapper.dependency.getNamespaceId
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream

fun mcpConfigFile(
    project: Project,
    userdevFiles: FileCollection,
): Provider<File> {
    val userdev = Userdev.fromFile(userdevFiles.singleFile)!!

    return project.provider {
        resolveFile(project, userdev.config.mcp)
    }
}

fun mcpConfigExtraRemappingFiles(
    project: Project,
    mcpConfigFile: File,
): Provider<Map<String, File>> {
    val mcp = McpConfigFile.fromFile(mcpConfigFile)!!

    val metadata =
        project.lazyProvider {
            runBlocking {
                project.extension<MinecraftCodevExtension>().getVersionList().version(mcp.config.version)
            }
        }

    val javaExecutable =
        metadata.flatMap {
            it.javaVersion.executable(project).map(RegularFile::getAsFile)
        }

    val clientMappings =
        metadata.map {
            runBlocking {
                downloadMinecraftFile(project, it, MinecraftDownloadVariant.ClientMappings)!!.toFile()
            }
        }

    val mergeMappingsJarFile =
        project.lazyProvider {
            resolveFile(
                project,
                mcp.config.functions
                    .getValue("mergeMappings")
                    .version,
            )
        }

    val mapProperty = project.objects.mapProperty(String::class.java, File::class.java)

    mapProperty.put("javaExecutable", javaExecutable)
    mapProperty.put("mergeMappingsJarFile", mergeMappingsJarFile)
    mapProperty.put("clientMappings", clientMappings)

    return mapProperty
}

class McpConfigMappingResolutionRule : ZipMappingResolutionRule {
    override fun load(
        path: Path,
        fileSystem: FileSystem,
        isJar: Boolean,
        data: MappingResolutionData,
    ): Boolean {
        val mcpConfigFile = McpConfigFile.fromFile(path.toFile()) ?: return false

        val javaExecutable = data.extraFiles.getValue("javaExecutable")
        val mergeMappingsJarFile = data.extraFiles.getValue("mergeMappingsJarFile")
        val clientMappings = data.extraFiles.getValue("clientMappings")

        zipFileSystem(mcpConfigFile.source.toPath()).use { (mcpFs) ->
            val mappings =
                if (mcpConfigFile.config.official) {
                    val mergeMappings =
                        runBlocking {
                            McpAction(
                                data.execOperations,
                                javaExecutable,
                                mergeMappingsJarFile,
                                mcpConfigFile,
                                mcpConfigFile.config.functions
                                    .getValue("mergeMappings")
                                    .args,
                                mapOf(
                                    "official" to clientMappings,
                                ),
                                nullOutputStream(),
                            )
                        }

                    mergeMappings.execute()
                } else {
                    mcpFs.getPath(mcpConfigFile.config.data.getValue("mappings")!!)
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
        }

        return true
    }
}



