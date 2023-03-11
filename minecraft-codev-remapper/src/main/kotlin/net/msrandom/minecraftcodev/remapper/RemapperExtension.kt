package net.msrandom.minecraftcodev.remapper

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.ResolveContext
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.configurationcache.extensions.serviceOf
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
    fun loadMappings(
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
    fun loadMappings(
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

open class RemapperExtension @Inject constructor(objectFactory: ObjectFactory, private val project: Project) {
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
                        if (rule.loadMappings(path, it.base, visitor, configuration, decorate, existingMappings, isJar, objects)) {
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

    fun loadMappings(files: Configuration, objects: ObjectFactory, resolve: Boolean) = mappingsCache.computeIfAbsent(files) { configuration ->
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        if (resolve) {
            for (dependency in configuration.allDependencies) {
                for (file in configuration.files(dependency)) {
                    for (rule in mappingsResolution.get()) {
                        if (rule.loadMappings(file.toPath(), file.extension, tree, configuration, { DigestInputStream(this, md) }, tree, objects)) {
                            break
                        }
                    }
                }
            }
        } else {
            val resolvers by lazy {
                val detachedConfiguration = project.configurations.detachedConfiguration()
                val resolutionStrategy = detachedConfiguration.resolutionStrategy
                val resolvers = mutableListOf<ComponentResolvers>()
                for (resolverFactory in project.gradle.resolverFactories) {
                    resolverFactory.create(detachedConfiguration as ResolveContext, resolvers)
                }

                resolvers.add(
                    project.serviceOf<ResolveIvyFactory>().create(
                        detachedConfiguration.name,
                        resolutionStrategy as ResolutionStrategyInternal,
                        project.serviceOf<RepositoriesSupplier>().get(),
                        project.serviceOf<GlobalDependencyResolutionRules>().componentMetadataProcessorFactory,
                        ImmutableAttributes.EMPTY,
                        null,
                        project.serviceOf(),
                        project.serviceOf()
                    )
                )

                ComponentResolversChain(resolvers, project.serviceOf(), project.serviceOf())
            }

            for (dependency in configuration.allDependencies) {
                project.visitConfigurationFiles({ resolvers }, configuration, dependency) { file ->
                    for (rule in mappingsResolution.get()) {
                        if (rule.loadMappings(file.toPath(), file.extension, tree, configuration, { DigestInputStream(this, md) }, tree, objects)) {
                            break
                        }
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
