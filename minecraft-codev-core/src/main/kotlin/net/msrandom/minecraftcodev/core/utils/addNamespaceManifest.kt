package net.msrandom.minecraftcodev.core.utils

import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

const val CODEV_MAPPING_NAMESPACE_ATTRIBUTE = "Codev-Mapping-Namespace"

fun addNamespaceManifest(
    fileSystem: FileSystem,
    namespace: String,
) = addNamespaceManifest(
    fileSystem.getPath("META-INF", "MANIFEST.MF"),
    namespace,
)

fun addNamespaceManifest(
    manifestPath: Path,
    namespace: String,
) {
    val manifest = if (manifestPath.exists()) manifestPath.inputStream().use(::Manifest) else Manifest()

    manifest.mainAttributes.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.mainAttributes.putValue(CODEV_MAPPING_NAMESPACE_ATTRIBUTE, namespace)

    manifestPath.parent.createDirectories()

    manifestPath.outputStream(StandardOpenOption.CREATE).use(manifest::write)
}
