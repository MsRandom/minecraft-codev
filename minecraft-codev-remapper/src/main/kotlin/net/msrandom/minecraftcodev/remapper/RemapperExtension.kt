package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import org.gradle.api.artifacts.Configuration
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.internal.hash.HashCode
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

typealias MappingResolutionRule = (path: Path, extension: String, visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView) -> Boolean

fun interface ZipMappingResolutionRule {
    operator fun invoke(fileSystem: FileSystem, visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView, isJar: Boolean): Boolean
}

fun interface ExtraFileRemapper {
    operator fun invoke(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int)
}

open class RemapperExtension @Inject constructor(objects: ObjectFactory) {
    private val mappingResolutionRules = mutableListOf<MappingResolutionRule>(
        { path, extension, visitor, decorate, existingMappings ->
            val isJar = extension == "jar"

            var result = false

            if (isJar || extension == "zip") {
                MinecraftCodevPlugin.zipFileSystem(path).use {
                    for (rule in zipMappingsResolution.get()) {
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

    val zipMappingsResolution: ListProperty<ZipMappingResolutionRule> = objects.listProperty(ZipMappingResolutionRule::class.java)
    val extraFileRemappers: ListProperty<ExtraFileRemapper> = objects.listProperty(ExtraFileRemapper::class.java)

    private val mappingsCache = ConcurrentHashMap<Configuration, Mappings>()

    fun loadMappings(files: Configuration) = mappingsCache.computeIfAbsent(files) {
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        for (dependency in it.dependencies) {
            for (file in it.files(dependency)) {
                for (rule in mappingResolutionRules) {
                    if (rule(file.toPath(), file.extension, tree, { DigestInputStream(this, md) }, tree)) {
                        break
                    }
                }
            }
        }

        Mappings(
            tree,
            HashCode.fromBytes(md.digest())
        )
    }

    fun mappingsResolution(action: MappingResolutionRule) {
        mappingResolutionRules.add(action)
    }

    fun remapFiles(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int) {
        for (extraMapper in extraFileRemappers.get()) {
            extraMapper(mappings, directory, sourceNamespaceId, targetNamespaceId)
        }
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
