package net.msrandom.minecraftcodev.forge.task

import kotlinx.coroutines.runBlocking
import net.minecraftforge.accesstransformer.TransformerProcessor
import net.msrandom.minecraftcodev.core.resolve.*
import net.msrandom.minecraftcodev.core.task.CachedMinecraftTask
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.Userdev
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.copyTo
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeLines

internal fun resolveFile(
    configurationContainer: ConfigurationContainer,
    dependencyHandler: DependencyHandler,
    name: String,
): File =
    configurationContainer
        .detachedConfiguration(dependencyHandler.create(name))
        .apply { isTransitive = false }
        .singleFile

internal fun resolveFile(
    project: Project,
    name: String,
) = resolveFile(project.configurations, project.dependencies, name)

@CacheableTask
abstract class ResolvePatchedMinecraft : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val patches: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    abstract val output: RegularFileProperty
        @OutputFile get

    abstract val configurationContainer: ConfigurationContainer
        @Inject get

    abstract val dependencyHandler: DependencyHandler
        @Inject get

    abstract val javaToolchainService: JavaToolchainService
        @Inject get

    abstract val execOperations: ExecOperations
        @Inject get

    init {
        output.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("minecraft-$it-patched.jar")
                },
            ),
        )
    }

    @TaskAction
    private fun resolve() {
        runBlocking {
            val cacheDirectory = cacheParameters.directory.getAsPath()
            val isOffline = cacheParameters.isOffline.get()

            val metadata = cacheParameters.versionList().version(version.get())

            val clientJar = downloadMinecraftClient(cacheDirectory, metadata, isOffline)

            val extractionState = getExtractionState(cacheDirectory, metadata, isOffline)!!

            val serverJar = extractionState.result

            val libraries =
                configurationContainer.detachedConfiguration(
                    *getClientDependencies(cacheDirectory, metadata, isOffline).map(dependencyHandler::create).toTypedArray(),
                )

            val userdev = Userdev.fromFile(patches.singleFile)!!

            val mcpConfigFile =
                McpConfigFile.fromFile(
                    configurationContainer.detachedConfiguration(dependencyHandler.create(userdev.config.mcp)).singleFile,
                )!!

            val javaExecutable =
                metadata.javaVersion
                    .executable(javaToolchainService)
                    .get()
                    .asFile

            fun mcpAction(
                name: String,
                template: Map<String, Any>,
                stdout: OutputStream?,
            ): McpAction {
                val function = mcpConfigFile.config.functions.getValue(name)

                return McpAction(
                    execOperations,
                    javaExecutable,
                    resolveFile(configurationContainer, dependencyHandler, function.version),
                    mcpConfigFile,
                    function.args,
                    template,
                    stdout,
                )
            }

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

                    val patch =
                        PatchMcpAction(
                            execOperations,
                            javaExecutable,
                            mcpConfigFile,
                            userdev,
                            patchLog,
                            configurationContainer,
                            dependencyHandler,
                        )

                    val official = mcpConfigFile.config.official
                    val notchObf = userdev.config.notchObf

                    if (official) {
                        val clientMappings = downloadMinecraftFile(cacheDirectory, metadata, MinecraftDownloadVariant.ClientMappings, cacheParameters.isOffline.get())!!

                        temporaryDir.resolve("patch.log").outputStream().use {
                            val mergeMappings =
                                mcpAction(
                                    "mergeMappings",
                                    mapOf(
                                        "official" to clientMappings,
                                    ),
                                    it,
                                )

                            merge
                                .execute()
                                .let { merged ->
                                    mergeMappings.execute().let { mappings ->
                                        rename.execute("input" to merged, "mappings" to mappings)
                                    }
                                }.let(patch::execute)
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
                                merge
                                    .execute()
                                    .let(patch::execute)
                                    .let(rename::execute)
                            } else {
                                merge
                                    .execute()
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
                        *atFiles
                            .flatMap { at ->
                                listOf("--atFile", at.toAbsolutePath().toString())
                            }.toTypedArray(),
                    )
                }
            } finally {
                System.setErr(err)
            }

            addMinecraftMarker(outputFile.toPath())
        }
    }
}
