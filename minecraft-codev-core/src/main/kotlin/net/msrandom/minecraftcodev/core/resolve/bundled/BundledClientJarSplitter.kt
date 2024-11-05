package net.msrandom.minecraftcodev.core.resolve.bundled

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftClient
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter.useFileSystems
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter.withAssets
import net.msrandom.minecraftcodev.core.utils.*
import org.gradle.api.Project
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

object BundledClientJarSplitter {
    internal suspend fun split(
        cacheDirectory: Path,
        metadata: MinecraftVersionMetadata,
        server: Path,
        isOffline: Boolean,
    ): Path {
        val client = downloadMinecraftClient(cacheDirectory, metadata, isOffline)
        val outputClient = clientJarPath(cacheDirectory, metadata.id)

        outputClient.parent.createDirectories()

        useFileSystems { handle ->
            val clientFs = zipFileSystem(client).also(handle)
            val serverFs = zipFileSystem(server).also(handle)
            val newClientFs = zipFileSystem(outputClient, create = true).also(handle)

            clientFs.getPath("/").walk {
                for (clientEntry in this) {
                    val name = clientEntry.toString()
                    if (name.endsWith(".class")) {
                        val serverEntry = serverFs.getPath(name)

                        if (serverEntry.notExists()) {
                            val output = newClientFs.getPath(name)
                            output.parent?.createDirectories()
                            clientEntry.copyTo(output, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    }
                }
            }

            clientFs.withAssets { path ->
                val name = path.toString()
                if (serverFs.getPath(
                        name,
                    ).notExists() || ("lang" in name || (path.parent.name == "assets" && path.name.startsWith('.')))
                ) {
                    val newPath = newClientFs.getPath(name)
                    newPath.parent?.createDirectories()
                    path.copyTo(newPath, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }

        return outputClient
    }
}
