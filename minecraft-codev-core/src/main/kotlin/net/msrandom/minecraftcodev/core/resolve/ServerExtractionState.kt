package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.resolve.bundled.ServerExtractor
import net.msrandom.minecraftcodev.core.resolve.legacy.ServerFixer
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.internal.operations.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

private val extractionStates = ConcurrentHashMap<String, Lazy<ServerExtractionResult?>>()

fun getExtractionState(cacheManager: CodevCacheManager, buildOperationExecutor: BuildOperationExecutor, manifest: MinecraftVersionMetadata, serverJarProvider: () -> File?, clientJar: () -> File) =
    extractionStates.computeIfAbsent(manifest.id) {
        lazy {
            val extractedServerData = cacheManager.rootPath
                .resolve("extracted-servers")
                .resolve(manifest.id)

            val extractedJar = extractedServerData.resolve("server.jar")
            val libraries = extractedServerData.resolve("libraries.txt")
            val bundledMark = extractedServerData.resolve("bundle")

            if (extractedJar.exists() && libraries.exists()) {
                val commonLibraries = libraries.readLines().map(ModuleLibraryIdentifier::load)
                val isBundled = bundledMark.exists()

                ServerExtractionResult(extractedJar, isBundled, commonLibraries)
            } else {
                val serverJar = serverJarProvider()

                if (serverJar == null) {
                    null
                } else {
                    buildOperationExecutor.call(object : CallableBuildOperation<ServerExtractionResult> {

                        override fun description() = BuildOperationDescriptor
                            .displayName("Extracting $serverJar")
                            .progressDisplayName(extractedJar.toString())
                            .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            val temporaryServer = Files.createTempFile("server-", ".tmp.jar")
                            val commonLibraries: Collection<ModuleLibraryIdentifier>
                            val isBundled: Boolean

                            zipFileSystem(serverJar.toPath()).use { serverFs ->
                                val librariesPath = serverFs.getPath("META-INF/libraries.list")

                                if (librariesPath.exists()) {
                                    // New server Jar, just extract it and populate the library list
                                    isBundled = true
                                    commonLibraries = ServerExtractor.extract(manifest.id, temporaryServer, serverFs, librariesPath)
                                } else {
                                    // Old server Jar, strip the libraries out manually
                                    isBundled = false
                                    serverJar.toPath().copyTo(temporaryServer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                                    commonLibraries = ServerFixer.removeLibraries(manifest, temporaryServer, serverFs, clientJar().toPath())
                                }
                            }

                            extractedServerData.createDirectories()
                            temporaryServer.copyTo(extractedJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                            libraries.writeLines(commonLibraries.map(ModuleLibraryIdentifier::toString))

                            if (isBundled) {
                                Files.createFile(bundledMark)
                            }

                            ServerExtractionResult(extractedJar, isBundled, commonLibraries)
                        }
                    })
                }
            }
        }
    }

data class ServerExtractionResult(val result: Path, val isBundled: Boolean, val libraries: Collection<ModuleLibraryIdentifier>)
