package net.msrandom.minecraftcodev.forge.task

import net.minecraftforge.accesstransformer.TransformerProcessor
import net.msrandom.minecraftcodev.core.resolve.*
import net.msrandom.minecraftcodev.core.task.CachedMinecraftTask
import net.msrandom.minecraftcodev.core.task.versionList
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.forge.McpConfigFile
import net.msrandom.minecraftcodev.forge.Userdev
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import java.io.Closeable
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.*

@CacheableTask
abstract class ResolvePatchedMinecraft : CachedMinecraftTask() {
    abstract val version: Property<String>
        @Input get

    abstract val libraries: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    abstract val patches: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    abstract val universal: ConfigurableFileCollection
        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        get

    abstract val output: RegularFileProperty
        @OutputFile get

    abstract val clientExtra: RegularFileProperty
        @OutputFile get

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

        clientExtra.convention(
            project.layout.file(
                version.map {
                    temporaryDir.resolve("minecraft-$it-patched-client-extra.zip")
                },
            ),
        )
    }

    private fun resolve(cacheDirectory: Path, outputPath: Path) {
        val isOffline = cacheParameters.getIsOffline().get()

        val metadata = cacheParameters.versionList().version(version.get())

        val clientJar = downloadMinecraftClient(cacheDirectory, metadata, isOffline)

        val extractionState = getExtractionState(cacheDirectory, metadata, isOffline)!!

        val serverJar = extractionState.result

        val userdevFile = patches.filter { "userdev" in it.name }.singleFile
        val userdev = Userdev.fromFile(userdevFile)!!

        val mcpConfigFile =
            McpConfigFile.fromFile(dependencyFile(patches, userdev.config.mcp))!!

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
                patches,
                function,
                mcpConfigFile,
                template,
                stdout,
            )
        }

        val librariesFile = Files.createTempFile("libraries", ".txt")

        librariesFile.writeLines(libraries.flatMap { listOf("-e", it.absolutePath) })

        val clientExtra = clientExtra.get()

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
                        patches,
                        mcpConfigFile,
                        userdev,
                        universal.singleFile,
                        patchLog,
                    )

                val official = mcpConfigFile.config.official
                val notchObf = userdev.config.notchObf

                zipFileSystem(mcpConfigFile.source).use { fs ->
                    if (official) {
                        val clientMappings = downloadMinecraftFile(cacheDirectory, metadata, MinecraftDownloadVariant.ClientMappings, isOffline)!!

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
                                .execute(fs)
                                .let { merged ->
                                    mergeMappings.execute(fs).let { mappings ->
                                        rename.execute(fs, "input" to merged, "mappings" to mappings)
                                    }
                                }.let { patch.execute(fs, it) }
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
                                    .execute(fs)
                                    .let { patch.execute(fs, it) }
                                    .let { rename.execute(fs, it) }
                            } else {
                                merge
                                    .execute(fs)
                                    .let { rename.execute(fs, it) }
                                    .let { patch.execute(fs, it) }
                            }

                        inject.execute(fs, base)
                    }
                }
            } finally {
                logFiles.forEach(Closeable::close)
            }

        val atFiles =
            zipFileSystem(userdev.source.toPath()).use { fs ->
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
                    outputPath.toString(),
                    *atFiles
                        .flatMap { at ->
                            listOf("--atFile", at.toAbsolutePath().toString())
                        }.toTypedArray(),
                )
            }
        } finally {
            System.setErr(err)
        }

        clientJar.copyTo(clientExtra.toPath(), StandardCopyOption.REPLACE_EXISTING)

        zipFileSystem(clientExtra.toPath()).use { clientZip ->
            clientZip.getPath("/").walk {
                for (path in filter(Path::isRegularFile)) {
                    if (path.toString().endsWith(".class") || path.startsWith("/META-INF")) {
                        path.deleteExisting()
                    }
                }
            }
        }

        addMinecraftMarker(outputPath)
    }

    @TaskAction
    fun resolve() {
        val cacheDirectory = cacheParameters.directory.getAsPath()

        cacheExpensiveOperation(cacheDirectory, "patch", patches, output.getAsPath()) {
            resolve(cacheDirectory, it)
        }
    }
}
