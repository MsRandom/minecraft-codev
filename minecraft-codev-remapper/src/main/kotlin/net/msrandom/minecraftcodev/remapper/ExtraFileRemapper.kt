package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.tree.MappingTreeView
import java.nio.file.FileSystem
import java.util.*

fun interface ExtraFileRemapper {
    operator fun invoke(
        mappings: MappingTreeView,
        fileSystem: FileSystem,
        sourceNamespace: String,
        targetNamespace: String,
    )
}

val extraFileRemappers = ServiceLoader.load(ExtraFileRemapper::class.java).toList()

fun remapFiles(
    mappings: MappingTreeView,
    fileSystem: FileSystem,
    sourceNamespace: String,
    targetNamespace: String,
) {
    for (extraMapper in extraFileRemappers) {
        extraMapper(mappings, fileSystem, sourceNamespace, targetNamespace)
    }
}
