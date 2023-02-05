package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
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
import kotlin.io.path.inputStream

fun interface MappingResolutionRule {
    operator fun invoke(
        path: Path,
        extension: String,
        visitor: MappingVisitor,
        configuration: Configuration,
        decorate: InputStream.() -> InputStream,
        existingMappings: MappingTreeView,
        objects: ObjectFactory
    ): Boolean
}

fun interface ZipMappingResolutionRule {
    operator fun invoke(
        path: Path,
        fileSystem: FileSystem,
        visitor: MappingVisitor,
        configuration: Configuration,
        decorate: InputStream.() -> InputStream,
        existingMappings: MappingTreeView,
        isJar: Boolean,
        objects: ObjectFactory
    ): Boolean
}

fun interface ExtraFileRemapper {
    operator fun invoke(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int)
}

open class RemapperExtension @Inject constructor(objectFactory: ObjectFactory) {
    val mappingsResolution: ListProperty<MappingResolutionRule> = objectFactory.listProperty(MappingResolutionRule::class.java)
    val zipMappingsResolution: ListProperty<ZipMappingResolutionRule> = objectFactory.listProperty(ZipMappingResolutionRule::class.java)
    val extraFileRemappers: ListProperty<ExtraFileRemapper> = objectFactory.listProperty(ExtraFileRemapper::class.java)

    private val mappingsCache = ConcurrentHashMap<Configuration, Mappings>()

    init {
        mappingsResolution.add { path, extension, visitor, configuration, decorate, existingMappings, objects ->
            val isJar = extension == "jar"

            var result = false

            if (isJar || extension == "zip") {
                zipFileSystem(path).use {
                    for (rule in zipMappingsResolution.get()) {
                        if (rule(path, it, visitor, configuration, decorate, existingMappings, isJar, objects)) {
                            result = true
                            break
                        }
                    }
                }
            }

            result
        }

        mappingsResolution.add { path, extension, visitor, _, decorate, _, _ ->
            if (extension == "txt") {
                path.inputStream().decorate().reader().use {
                    ProGuardReader.read(it, MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE, MappingsNamespace.OBF, MappingSourceNsSwitch(visitor, MappingsNamespace.OBF))
                }

                true
            } else {
                false
            }
        }
    }

    fun loadMappings(files: Configuration, objects: ObjectFactory) = mappingsCache.computeIfAbsent(files) {
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        for (dependency in it.allDependencies) {
            for (file in it.files(dependency)) {
                for (rule in mappingsResolution.get()) {
                    if (rule(file.toPath(), file.extension, tree, files, { DigestInputStream(this, md) }, tree, objects)) {
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

    fun remapFiles(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int) {
        for (extraMapper in extraFileRemappers.get()) {
            extraMapper(mappings, directory, sourceNamespaceId, targetNamespaceId)
        }
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
