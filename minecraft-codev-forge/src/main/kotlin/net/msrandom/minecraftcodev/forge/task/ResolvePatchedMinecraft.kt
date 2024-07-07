package net.msrandom.minecraftcodev.forge.task

import kotlinx.coroutines.runBlocking
import net.minecraftforge.accesstransformer.TransformerProcessor
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftClient
import net.msrandom.minecraftcodev.core.resolve.getClientDependencies
import net.msrandom.minecraftcodev.core.resolve.getExtractionState
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.Userdev
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeLines

abstract class ResolvePatchedMinecraft : DefaultTask() {
    abstract val version: Property<String>
        @Input get

    abstract val clientMappings: RegularFileProperty
        @InputFile get

    abstract val patches: ConfigurableFileCollection
        @InputFiles get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("$it.jar")
                },
            ),
        )
    }

    @TaskAction
    fun resolve() {
        runBlocking {
            val metadata = project.extension<MinecraftCodevExtension>().getVersionList().version(version.get())

            val clientJar = downloadMinecraftClient(project, metadata)

            val clientMappings = clientMappings.asFile.get().toPath()

            val extractionState = getExtractionState(project, metadata)!!

            val serverJar = extractionState.result

            val libraries = project.configurations.detachedConfiguration(*getClientDependencies(project, metadata).toTypedArray())

            val userdev = Userdev.fromFile(patches.singleFile)!!

            val mcpConfigFile =
                McpConfigFile.fromFile(
                    project.configurations.detachedConfiguration(project.dependencies.create(userdev.config.mcp)).singleFile,
                )!!

            fun mcpAction(
                name: String,
                template: Map<String, Any>,
            ) = McpAction(project, metadata, mcpConfigFile, mcpConfigFile.config.functions.getValue(name), template)

            val librariesFile = Files.createTempFile("libraries", ".txt")

            librariesFile.writeLines(libraries.flatMap { listOf("-e", it.absolutePath) })

            val merge =
                mcpAction(
                    "merge",
                    mapOf(
                        "client" to clientJar,
                        "server" to serverJar,
                        "version" to version.get(),
                    ),
                )

            val rename =
                mcpAction(
                    "rename",
                    mapOf(
                        "libraries" to librariesFile,
                    ),
                )

            val patch = PatchMcpAction(project, metadata, mcpConfigFile, userdev)

            val official = mcpConfigFile.config.official
            val notchObf = userdev.config.notchObf

            val outputFile = output.get()

            val patched =
                if (official) {
                    val mergeMappings =
                        mcpAction(
                            "mergeMappings",
                            mapOf(
                                "official" to clientMappings,
                            ),
                        )

                    merge.execute()
                        .let { merged ->
                            mergeMappings.execute().let { mappings ->
                                rename.execute("input" to merged, "mappings" to mappings)
                            }
                        }
                        .let(patch::execute)
                } else {
                    val inject =
                        mcpAction(
                            "mcinject",
                            mapOf(
                                "log" to outputFile.asFile.toPath().parent.resolve("mcinject.log"),
                            ),
                        )

                    val base =
                        if (notchObf) {
                            merge.execute()
                                .let(patch::execute)
                                .let(rename::execute)
                        } else {
                            merge.execute()
                                .let(rename::execute)
                                .let(patch::execute)
                        }

                    inject.execute(base)
                }

            val atFiles =
                zipFileSystem(userdev.source.toPath()).use { (fs) ->
                    userdev.config.ats.flatMap {
                        val path = fs.getPath(it)

                        val paths =
                            if (path.isDirectory()) {
                                path.listDirectoryEntries()
                            } else {
                                path
                            }

                        paths.map {
                            val temp = Files.createTempFile("at-", ".tmp.cfg")

                            it.copyTo(temp, StandardCopyOption.REPLACE_EXISTING)

                            temp
                        }
                    }
                }

            TransformerProcessor.main(
                "--inJar",
                patched.toAbsolutePath().toString(),
                "--outJar",
                outputFile.toString(),
                *atFiles.flatMap {
                    listOf("--atFile", it.toAbsolutePath().toString())
                }.toTypedArray(),
            )
        }
    }
}
