package net.msrandom.minecraftcodev.remapper

import kotlinx.serialization.json.decodeFromStream
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.ProGuardReader
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.ResolutionData
import net.msrandom.minecraftcodev.core.dependency.resolverFactories
import net.msrandom.minecraftcodev.core.resolutionRules
import net.msrandom.minecraftcodev.core.utils.addNamespaceManifest
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.core.zipResolutionRules
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleDependency
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
import java.nio.file.FileSystem
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.io.path.inputStream
import kotlin.io.path.notExists

class MappingTreeProvider(val tree: MemoryMappingTree) {
    fun withTree(sourceNamespace: String, action: Action<MemoryMappingTree>) {
        if (tree.srcNamespace != sourceNamespace) {
            // Need to switch source to match
            val newTree = MemoryMappingTree()

            tree.accept(MappingSourceNsSwitch(newTree, sourceNamespace))

            action.execute(newTree)

            newTree.accept(MappingSourceNsSwitch(tree, tree.srcNamespace))
        } else {
            action.execute(tree)
        }
    }
}

class MappingResolutionData(
    visitor: MappingTreeProvider,
    messageDigest: MessageDigest,

    val configuration: Configuration,
    val objectFactory: ObjectFactory,
) : ResolutionData<MappingTreeProvider>(visitor, messageDigest)

fun interface ExtraFileRemapper {
    operator fun invoke(mappings: MappingTreeView, fileSystem: FileSystem, sourceNamespace: String, targetNamespace: String)
}

fun interface RemapClasspathRule {
    operator fun invoke(sourceNamespace: String, targetNamespace: String, version: String, mappingsConfiguration: String): Iterable<ModuleDependency>
}

fun sourceNamespaceVisitor(data: MappingResolutionData, sourceNamespace: String) = if (data.visitor.tree.srcNamespace == sourceNamespace) {
    data.visitor.tree
} else {
    MappingSourceNsSwitch(data.visitor.tree, data.visitor.tree.srcNamespace)
}

open class RemapperExtension @Inject constructor(objectFactory: ObjectFactory, private val project: Project) {
    val zipMappingsResolution = objectFactory.zipResolutionRules<MappingResolutionData>()
    val mappingsResolution = objectFactory.resolutionRules(zipMappingsResolution)
    val extraFileRemappers: ListProperty<ExtraFileRemapper> = objectFactory.listProperty(ExtraFileRemapper::class.java)
    val remapClasspathRules: ListProperty<RemapClasspathRule> = objectFactory.listProperty(RemapClasspathRule::class.java)

    private val mappingsCache = ConcurrentHashMap<Configuration, Mappings>()

    init {
        mappingsResolution.add { path, extension, data ->
            if (extension == "txt" || extension == "map") {
                data.decorate(path.inputStream()).reader().use {
                    ProGuardReader.read(
                        it,
                        MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
                        MappingsNamespace.OBF,
                        sourceNamespaceVisitor(data, MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE)
                    )
                }

                true
            } else {
                false
            }
        }

        mappingsResolution.add { path, extension, data ->
            if (extension != "json") {
                return@add false
            }

            handleParchment(data, path)

            true
        }

        zipMappingsResolution.add { _, fileSystem, _, data ->
            val parchmentJson = fileSystem.getPath("parchment.json")

            if (parchmentJson.notExists()) {
                return@add false
            }

            handleParchment(data, parchmentJson)

            true
        }

        extraFileRemappers.add { _, fileSystem, _, targetNamespace ->
            addNamespaceManifest(fileSystem.getPath("META-INF", "MANIFEST.MF"), targetNamespace)
        }
    }

    private fun handleParchment(data: MappingResolutionData, path: Path) {
        data.visitor.withTree(MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE) {
            val visitor = it
            val parchment = data.decorate(path.inputStream()).use {
                json.decodeFromStream<Parchment>(it)
            }

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
    }

    fun loadMappings(files: Configuration, objects: ObjectFactory, resolve: Boolean) = mappingsCache.computeIfAbsent(files) { configuration ->
        val tree = MemoryMappingTree()
        val md = MessageDigest.getInstance("SHA1")

        val data = MappingResolutionData(MappingTreeProvider(tree), md, files, objects)

        if (resolve) {
            for (dependency in configuration.allDependencies) {
                for (file in configuration.files(dependency)) {
                    for (rule in mappingsResolution.get()) {
                        if (rule.load(file.toPath(), file.extension, data)) {
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
                        if (rule.load(file.toPath(), file.extension, data)) {
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

    fun remapFiles(mappings: MappingTreeView, fileSystem: FileSystem, sourceNamespace: String, targetNamespace: String) {
        for (extraMapper in extraFileRemappers.get()) {
            extraMapper(mappings, fileSystem, sourceNamespace, targetNamespace)
        }
    }

    data class Mappings(val tree: MappingTreeView, val hash: HashCode)
}
