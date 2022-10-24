package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import java.nio.file.FileSystem
import java.nio.file.Path

interface MappingsHandler {
    fun shouldHandle(file: Path)
    fun handle(file: Path, visitor: MappingVisitor)
}

interface ZipMappingsHandler {
    fun shouldHandle(fileSystem: FileSystem)
    fun handle(fileSystem: FileSystem, visitor: MappingVisitor)
}
