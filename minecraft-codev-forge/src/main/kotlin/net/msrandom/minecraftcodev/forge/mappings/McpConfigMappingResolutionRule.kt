package net.msrandom.minecraftcodev.forge.mappings

import com.google.common.io.ByteStreams.nullOutputStream
import net.fabricmc.mappingio.adapter.MappingNsCompleter
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.TsrgReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.VERSION_MANIFEST_URL
import net.msrandom.minecraftcodev.core.getVersionList
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.utils.getGlobalCacheDirectoryProvider
import net.msrandom.minecraftcodev.core.utils.lazyProvider
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin.Companion.SRG_MAPPINGS_NAMESPACE
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.task.McpAction
import net.msrandom.minecraftcodev.forge.task.resolveFile
import net.msrandom.minecraftcodev.remapper.MappingResolutionData
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.ZipMappingResolutionRule
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import java.io.File
import java.nio.file.FileSystem
import java.nio.file.Path
import kotlin.io.path.inputStream

fun mcpConfigDependency(
    project: Project,
    userdevFiles: FileCollection,
): Provider<String> =
    project.provider {
        val userdev = Userdev.fromFile(userdevFiles.singleFile)!!

        userdev.config.mcp
    }

fun mcpConfigExtraRemappingFiles(
    project: Project,
    mcpConfigFile: String,
    cacheDirectory: Provider<Directory> = getGlobalCacheDirectoryProvider(project),
    metadataUrl: Provider<String> = project.provider { VERSION_MANIFEST_URL },
    isOffline: Provider<Boolean> = project.provider { project.gradle.startParameter.isOffline },
): Provider<Map<String, File>> {
    val mcp = McpConfigFile.fromFile(resolveFile(project, mcpConfigFile))!!

    val metadata =
        project.lazyProvider {
            getVersionList(cacheDirectory.get().toPath(), metadataUrl.get(), isOffline.get()).version(mcp.config.version)
        }

    val javaExecutable =
        metadata.flatMap {
            it.javaVersion.executable(project).map(RegularFile::getAsFile)
        }

    val clientMappings =
        metadata.map {
            downloadMinecraftFile(
                cacheDirectory.get().toPath(),
                it,
                MinecraftDownloadVariant.ClientMappings,
                isOffline.get(),
            )!!.toFile()
        }

    val mergeMappingsJarFile =
        project.lazyProvider {
            mcp.config.functions["mergeMappings"]?.version?.let {
                resolveFile(
                    project,
                    it,
                )
            }
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
        val mcpConfigFile = McpConfigFile.fromFile(path, fileSystem) ?: return false

        val mappings =
            if (mcpConfigFile.config.official) {
                val javaExecutable = data.extraFiles.getValue("javaExecutable")
                val clientMappings = data.extraFiles.getValue("clientMappings")
                val mergeMappingsJarFile = data.extraFiles.getValue("mergeMappingsJarFile")

                val mergeMappings =
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
