package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.callWithStatus
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.resolve.bundled.ServerExtractor
import net.msrandom.minecraftcodev.core.resolve.legacy.ServerFixer
import org.gradle.internal.operations.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val extractionStates = ConcurrentHashMap<File, Lazy<ServerExtractionResult>>()

fun getExtractionState(buildOperationExecutor: BuildOperationExecutor, manifest: MinecraftVersionMetadata, serverJar: File, clientJar: () -> File) =
    extractionStates.computeIfAbsent(serverJar) {
        lazy {
            buildOperationExecutor.call(object : CallableBuildOperation<ServerExtractionResult> {
                val extractedJar = Files.createTempFile("server-", ".tmp.jar")

                override fun description() = BuildOperationDescriptor
                    .displayName("Extracting $serverJar")
                    .progressDisplayName(extractedJar.toString())
                    .metadata(BuildOperationCategory.TASK)

                override fun call(context: BuildOperationContext) = context.callWithStatus {
                    val commonLibraries: Collection<ModuleLibraryIdentifier>
                    val isBundled: Boolean

                    zipFileSystem(serverJar.toPath()).use { serverFs ->
                        val librariesPath = serverFs.getPath("META-INF/libraries.list")

                        if (librariesPath.exists()) {
                            // New server Jar, just extract it and populate the library list
                            isBundled = true
                            commonLibraries = ServerExtractor.extract(manifest.id, extractedJar, serverFs, librariesPath)
                        } else {
                            // Old server Jar, strip the libraries out manually
                            isBundled = false
                            commonLibraries = ServerFixer.removeLibraries(null, manifest, extractedJar, serverJar.toPath(), serverFs, clientJar().toPath())
                        }
                    }

                    ServerExtractionResult(extractedJar, isBundled, commonLibraries)
                }
            })
        }
    }

data class ServerExtractionResult(val result: Path, val isBundled: Boolean, val libraries: Collection<ModuleLibraryIdentifier>)
