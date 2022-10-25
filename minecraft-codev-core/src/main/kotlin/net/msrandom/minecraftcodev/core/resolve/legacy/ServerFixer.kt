package net.msrandom.minecraftcodev.core.resolve.legacy

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import org.gradle.api.logging.Logger
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

object ServerFixer {
    private val LIBRARY_PATHS = mapOf(
        "com.mojang:javabridge" to "/com/mojang/bridge",
        "com.mojang:datafixerupper" to "/com/mojang/datafixers",
        "net.sf.jopt-simple:jopt-simple" to "/joptsimple",
        "com.google.code.gson:gson" to "/com/google/gson",
        "com.google.guava:guava" to "/com/google",
        "commons-logging:commons-logging" to "/org/apache/commons/logging",
        "org.apache.commons:commons-lang3" to "/org/apache/commons/lang3",
        "org.apache.commons:commons-compress" to "/org/apache/commons/compress",
        "org.apache.logging.log4j:log4j-core" to "/org/apache/logging/log4j/core"
    )

    private fun testLibraryPath(path: Path, entry: Map.Entry<String, MutableList<Pair<String, MinecraftVersionMetadata.Library>>>): MinecraftVersionMetadata.Library? {
        if (entry.value.size == 1) {
            if (path.startsWith(entry.key)) {
                return entry.value[0].second
            }

            return null
        }

        return entry.value.firstOrNull { path.startsWith(it.first) }?.second
    }

    fun removeLibraries(logger: Logger?, manifest: MinecraftVersionMetadata, newServer: Path, server: Path, serverFs: FileSystem, client: Path): Collection<ModuleLibraryIdentifier> {
        val commonLibraries = mutableSetOf<MinecraftVersionMetadata.Library>()
        val libraryGroups = hashMapOf<String, MutableList<Pair<String, MinecraftVersionMetadata.Library>>>()

        for (library in manifest.libraries) {
            val groupName = '/' + library.name.group.replace('.', '/')
            val path = LIBRARY_PATHS["${library.name.group}:${library.name.module}"]
            if (path == null) {
                val name = "$groupName/${library.name.module}"

                val grouped = libraryGroups.computeIfAbsent(groupName) { mutableListOf() }
                grouped.add(name to library)
            } else {
                val grouped = libraryGroups.computeIfAbsent(path) { mutableListOf() }
                grouped.add(path to library)
            }
        }

        newServer.deleteIfExists()

        // Do this even if it's already done, as we need it for collecting the server libraries
        logger?.lifecycle(":Stripping ${manifest.id} server Jar")
        zipFileSystem(newServer, true).use { fixedFs ->
            zipFileSystem(client).use { clientFs ->
                val root = serverFs.getPath("/")
                WALK@ for (path in Files.walk(root).filter(Path::isRegularFile)) {
                    if (clientFs.getPath(path.toString()).notExists()) {
                        for (entry in libraryGroups) {
                            val library = testLibraryPath(path, entry)

                            if (library != null) {
                                commonLibraries.add(library)
                                continue@WALK
                            }
                        }

                        if ((path.parent != root || !path.toString().endsWith(".class")) && "net/minecraft" !in path.toString()) continue@WALK
                    }

                    val newPath = fixedFs.getPath(path.toString())
                    newPath.parent?.createDirectories()
                    path.copyTo(newPath)
                }
            }
        }

        return if (commonLibraries.isEmpty()) {
            // If we found no libraries, there's no point in modifying the file.
            server.copyTo(newServer, true)
            emptyList()
        } else {
            commonLibraries.map(MinecraftVersionMetadata.Library::name)
        }
    }
}
