package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.internal.hash.HashCode
import java.io.InputStream
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream

typealias MappingResolutionRule = (path: Path, extension: String, visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView) -> Boolean
typealias ZipMappingResolutionRule = FileSystem.(visitor: MappingVisitor, decorate: InputStream.() -> InputStream, existingMappings: MappingTreeView, isJar: Boolean) -> Boolean

open class RemapperExtension(internal val project: Project) {
    private val mappingResolutionRules = mutableListOf<MappingResolutionRule>()
    private val zipMappingResolutionRules = mutableListOf<ZipMappingResolutionRule>()
    private val mappingsCache = ConcurrentHashMap<Configuration, Mappings>()

    init {
        project.configurations.create(MAPPINGS_CONFIGURATION) { it.isCanBeConsumed = false }

        project.dependencies.attributesSchema {
            it.attribute(MAPPINGS_ATTRIBUTE)
            it.attribute(SOURCE_MAPPINGS_ATTRIBUTE)
        }

        mappingsResolution { path, extension, visitor, decorate, _ ->
            if (extension == "txt") {
                path.inputStream().decorate().reader().use {
                    ProGuardReader.read(it, MappingNamespace.NAMED, MappingNamespace.OBF, MappingSourceNsSwitch(visitor, MappingNamespace.OBF))
                }

                true
            } else {
                false
            }
        }

        mappingsResolution { path, extension, visitor, decorate, existingMappings ->
            val isJar = extension == "jar"

            var result = false

            if (isJar || extension == "zip") {
                zipFileSystem(path).use {
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
    }

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

    fun mappingsResolution(action: MappingResolutionRule) {
        mappingResolutionRules.add(action)
    }

    fun zipMappingsResolution(action: ZipMappingResolutionRule) {
        zipMappingResolutionRules.add(action)
    }

    companion object {
        const val MAPPINGS_CONFIGURATION = "mappings"

        val SOURCE_MAPPINGS_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.sourceMappings", String::class.java)
        val MAPPINGS_ATTRIBUTE: Attribute<String> = Attribute.of("net.msrandom.minecraftcodev.mappings", String::class.java)
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
