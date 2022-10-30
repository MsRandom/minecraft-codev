package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.internal.hash.HashCode
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

typealias MappingResolutionRule = (path: Path, extension: String, visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView) -> Boolean
typealias ZipMappingResolutionRule = FileSystem.(visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView, isJar: Boolean) -> Boolean
typealias ExtraFileRemapper = MappingTreeView.(directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int) -> Unit

open class RemapperExtension {
    private val mappingResolutionRules = mutableListOf<MappingResolutionRule>(
        { path, extension, visitor, decorate, existingMappings ->
            val isJar = extension == "jar"

            var result = false

            if (isJar || extension == "zip") {
                MinecraftCodevPlugin.zipFileSystem(path).use {
                    for (rule in zipMappingResolutionRules) {
                        if (rule(it, visitor, decorate, existingMappings, isJar)) {
                            result = true
                            break
                        }
                    }
                }
            }

            result
        }
    )

    private val zipMappingResolutionRules = mutableListOf<ZipMappingResolutionRule>()

    private val extraFileRemappers = mutableListOf<ExtraFileRemapper>()

    private val mappingsCache = ConcurrentHashMap<Configuration, Mappings>()

    fun loadMappings(files: Configuration) = mappingsCache.computeIfAbsent(files) {
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        for (file in files) {
            for (rule in mappingResolutionRules) {
                if (rule(file.toPath(), file.extension, tree, { DigestInputStream(this, md) }, tree)) {
                    break
                }
            }
        }

        Mappings(
            tree,
            HashCode.fromBytes(md.digest())
        )
    }

    fun loadMappings(files: FileCollection): MemoryMappingTree {
        val tree = MemoryMappingTree()

        for (file in files) {
            for (rule in mappingResolutionRules) {
                if (rule(file.toPath(), file.extension, tree, { this }, tree)) {
                    break
                }
            }
        }

        return tree
    }

    fun mappingsResolution(action: MappingResolutionRule) {
        mappingResolutionRules.add(action)
    }

    fun zipMappingsResolution(action: ZipMappingResolutionRule) {
        zipMappingResolutionRules.add(action)
    }

    fun extraFileRemapper(action: ExtraFileRemapper) {
        extraFileRemappers.add(action)
    }

    fun remapFiles(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int) {
        for (extraMapper in extraFileRemappers) {
            mappings.extraMapper(directory, sourceNamespaceId, targetNamespaceId)
        }
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
