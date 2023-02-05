package net.msrandom.minecraftcodev.core.resolve.bundled

import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter.useFileSystems
import net.msrandom.minecraftcodev.core.resolve.legacy.LegacyJarSplitter.withAssets
import net.msrandom.minecraftcodev.core.utils.walk
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.hash.ChecksumService
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

object BundledClientJarSplitter {
    fun split(
        checksumService: ChecksumService,
        pathFunction: (artifact: ModuleComponentArtifactIdentifier, sha1: String) -> Path,
        version: String,
        outputClient: Path,
        client: Path,
        server: Path
    ): Path = useFileSystems { handle ->
        outputClient.deleteExisting()

        val clientFs = zipFileSystem(client).also(handle)
        val serverFs = zipFileSystem(server).also(handle)
        val newClientFs = zipFileSystem(outputClient, true).also(handle)

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
            if (serverFs.getPath(name).notExists() || ("lang" in name || (path.parent.name == "assets" && path.name.startsWith('.')))) {
                val newPath = newClientFs.getPath(name)
                newPath.parent?.createDirectories()
                path.copyTo(newPath, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }

        outputClient
    }.let {
        val path = pathFunction(LegacyJarSplitter.makeId(MinecraftComponentResolvers.CLIENT_MODULE, version), checksumService.sha1(it.toFile()).toString())
        path.parent.createDirectories()
        it.copyTo(path)
        path
    }
}
