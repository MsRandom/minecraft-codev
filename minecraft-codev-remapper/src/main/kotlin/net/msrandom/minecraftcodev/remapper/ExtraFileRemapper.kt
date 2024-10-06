package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.utils.serviceLoader
import java.nio.file.FileSystem

fun interface ExtraFileRemapper {
    operator fun invoke(
        mappings: MappingTreeView,
        fileSystem: FileSystem,
        sourceNamespace: String,
        targetNamespace: String,
    )
}

val extraFileRemappers = serviceLoader<ExtraFileRemapper>()

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
