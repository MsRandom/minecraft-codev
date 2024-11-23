package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.*

private const val CODEV_MINECRAFT_MARKER = "Codev-Minecraft-Marker"

fun addMinecraftMarker(path: Path) {
    zipFileSystem(path).use { fs ->
        val manifestPath = fs.getPath(JarFile.MANIFEST_NAME)

        val manifest = if (manifestPath.exists()) {
            manifestPath.inputStream().use(::Manifest)
        } else {
            Manifest()
        }

        manifest.mainAttributes.putValue(CODEV_MINECRAFT_MARKER, true.toString())

        manifestPath.parent.createDirectories()

        manifestPath.outputStream().use(manifest::write)
    }
}

fun isCodevGeneratedMinecraftJar(path: Path) = zipFileSystem(path).use { fs ->
    val manifestPath = fs.getPath(JarFile.MANIFEST_NAME)

    if (manifestPath.notExists()) {
        return@use false
    }

    val manifest = manifestPath.inputStream().use(::Manifest)

    manifest.mainAttributes.getValue(CODEV_MINECRAFT_MARKER).toBoolean()
}
