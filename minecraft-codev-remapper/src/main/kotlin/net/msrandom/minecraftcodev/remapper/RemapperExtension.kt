package net.msrandom.minecraftcodev.remapper

import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import net.msrandom.minecraftcodev.core.utils.addNamespaceManifest
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
import kotlin.io.path.notExists

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
    operator fun invoke(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int, targetNamespace: String)
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
            if (extension == "txt" || extension == "map") {
                path.inputStream().decorate().reader().use {
                    ProGuardReader.read(it, MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE, MappingsNamespace.OBF, MappingSourceNsSwitch(visitor, MappingsNamespace.OBF))
                }

                true
            } else {
                false
            }
        }

        mappingsResolution.add { path, extension, visitor, _, decorate, _, _ ->
            if (extension == "json") {
                return@add false
            }

            handleParchment(path, visitor, decorate)

            true
        }

        zipMappingsResolution.add { _, fileSystem, visitor, _, decorate, existingMappings, _, _ ->
            val parchmentJson = fileSystem.getPath("parchment.json")

            if (parchmentJson.notExists()) {
                return@add false
            }

            if (existingMappings.srcNamespace != MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE) {
                // Need to switch source to match
                val newTree = MemoryMappingTree()

                existingMappings.accept(MappingSourceNsSwitch(newTree, MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE))

                handleParchment(parchmentJson, newTree, decorate)

                newTree.accept(MappingSourceNsSwitch(visitor, existingMappings.srcNamespace))
            } else {
                handleParchment(parchmentJson, visitor, decorate)
            }

            true
        }

        extraFileRemappers.add { _, directory, _, _, targetNamespace ->
            addNamespaceManifest(directory.resolve("META-INF").resolve("MANIFEST.MF"), targetNamespace)
        }
    }

    private fun handleParchment(path: Path, visitor: MappingVisitor, decorate: (InputStream) -> InputStream) {
        val parchment = decorate(path.inputStream()).use { json.decodeFromStream<Parchment>(it) }

        do {
            if (visitor.visitHeader()) {
                visitor.visitNamespaces(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE, emptyList())
            }

            if (visitor.visitContent()) {
                parchment.classes?.forEach CLASS_LOOP@{ classElement ->
                    if (!visitor.visitClass(classElement.name) || !visitor.visitElementContent(MappedElementKind.CLASS)) {
                        return@CLASS_LOOP
                    }

                    fun visitComment(element: Parchment.Element, type: MappedElementKind) {
                        element.javadoc?.let {
                            if (it.lines.isNotEmpty()) {
                                visitor.visitComment(type, it.lines.joinToString("\n"))
                            }
                        }
                    }

                    visitComment(classElement, MappedElementKind.CLASS)

                    classElement.fields?.forEach FIELD_LOOP@{ fieldElement ->
                        if (!visitor.visitField(fieldElement.name, fieldElement.descriptor) || !visitor.visitElementContent(MappedElementKind.METHOD)) {
                            return@FIELD_LOOP
                        }

                        visitComment(fieldElement, MappedElementKind.FIELD)
                    }

                    classElement.methods?.forEach METHOD_LOOP@{ methodElement ->
                        if (!visitor.visitMethod(methodElement.name, methodElement.descriptor) || !visitor.visitElementContent(MappedElementKind.METHOD)) {
                            return@METHOD_LOOP
                        }

                        visitComment(methodElement, MappedElementKind.METHOD)

                        methodElement.parameters?.forEach { parameterElement ->
                            visitor.visitMethodArg(parameterElement.index, parameterElement.index, parameterElement.name)

                            visitComment(parameterElement, MappedElementKind.METHOD_ARG)
                        }
                    }
                }
            }
        } while (!visitor.visitEnd())
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

    fun remapFiles(mappings: MappingTreeView, directory: Path, sourceNamespaceId: Int, targetNamespaceId: Int, targetNamespace: String) {
        for (extraMapper in extraFileRemappers.get()) {
            extraMapper(mappings, directory, sourceNamespaceId, targetNamespaceId, targetNamespace)
        }
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
