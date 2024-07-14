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
import java.io.Closeable
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeLines

@CacheableTask
abstract class ResolvePatchedMinecraft : DefaultTask() {
    abstract val version: Property<String>
        @Input get

    abstract val clientMappings: RegularFileProperty
        @PathSensitive(PathSensitivity.RELATIVE)
        @InputFile
        get

    abstract val patches: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    abstract val output: RegularFileProperty
        @OutputFile get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("patched-$it.jar")
                },
            ),
        )
    }

    @TaskAction
    private fun resolve() {
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
                stdout: OutputStream?,
            ) = McpAction(
                project,
                metadata,
                mcpConfigFile,
                mcpConfigFile.config.functions.getValue(name),
                template,
                stdout,
            )

            val librariesFile = Files.createTempFile("libraries", ".txt")

            librariesFile.writeLines(libraries.flatMap { listOf("-e", it.absolutePath) })

            val outputFile = output.get()

            val patchLog = temporaryDir.resolve("patch.log").outputStream()
            val renameLog = temporaryDir.resolve("rename.log").outputStream()

            val logFiles = listOf(patchLog, renameLog)

            val patched =
                try {
                    val merge =
                        mcpAction(
                            "merge",
                            mapOf(
                                "client" to clientJar,
                                "server" to serverJar,
                                "version" to version.get(),
                            ),
                            null,
                        )

                    val rename =
                        mcpAction(
                            "rename",
                            mapOf(
                                "libraries" to librariesFile,
                            ),
                            renameLog,
                        )

                    val patch = PatchMcpAction(project, metadata, mcpConfigFile, userdev, patchLog)

                    val official = mcpConfigFile.config.official
                    val notchObf = userdev.config.notchObf

                    if (official) {
                        temporaryDir.resolve("patch.log").outputStream().use {
                            val mergeMappings =
                                mcpAction(
                                    "mergeMappings",
                                    mapOf(
                                        "official" to clientMappings,
                                    ),
                                    it,
                                )

                            merge.execute()
                                .let { merged ->
                                    mergeMappings.execute().let { mappings ->
                                        rename.execute("input" to merged, "mappings" to mappings)
                                    }
                                }
                                .let(patch::execute)
                        }
                    } else {
                        val inject =
                            mcpAction(
                                "mcinject",
                                mapOf(
                                    "log" to temporaryDir.resolve("mcinject.log"),
                                ),
                                null,
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
                } finally {
                    logFiles.forEach(Closeable::close)
                }

            val atFiles =
                zipFileSystem(userdev.source.toPath()).use { (fs) ->
                    userdev.config.ats.flatMap {
                        val path = fs.getPath(it)

                        val paths =
                            if (path.isDirectory()) {
                                path.listDirectoryEntries()
                            } else {
                                listOf(path)
                            }

                        paths.map {
                            val temp = Files.createTempFile("at-", ".tmp.cfg")

                            it.copyTo(temp, StandardCopyOption.REPLACE_EXISTING)

                            temp
                        }
                    }
                }

            val err = System.err

            try {
                temporaryDir.resolve("at.log").outputStream().use {
                    System.setErr(PrintStream(it))

                    TransformerProcessor.main(
                        "--inJar",
                        patched.toAbsolutePath().toString(),
                        "--outJar",
                        outputFile.toString(),
                        *atFiles.flatMap { at ->
                            listOf("--atFile", at.toAbsolutePath().toString())
                        }.toTypedArray(),
                    )
                }
            } finally {
                System.setErr(err)
            }
        }
    }
}
