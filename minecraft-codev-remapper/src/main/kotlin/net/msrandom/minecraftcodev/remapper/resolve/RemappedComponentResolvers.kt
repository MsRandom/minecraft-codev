package net.msrandom.minecraftcodev.remapper.resolve

import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny2Writer
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.IvyDependencyDescriptorFactoriesProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentIdentifier
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.allArtifacts
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import net.msrandom.minecraftcodev.remapper.FieldAddDescVisitor
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.dependency.DslOriginRemappedDependencyMetadata
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadata
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadataWrapper
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.*
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import java.nio.file.Path
import java.util.*
import javax.inject.Inject
import kotlin.io.path.*

open class RemappedComponentResolvers @Inject constructor(
    private val configuration: Configuration,
    private val resolvers: ComponentResolversChainProvider,
    private val dependencyDescriptorFactories: IvyDependencyDescriptorFactoriesProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val attributesFactory: ImmutableAttributesFactory,
    private val instantiator: NamedObjectInstantiator,
    private val versionSelectorSchema: VersionSelectorScheme,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("remapped")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { RemappedArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun buildClasspath(
        selectedVariants: MutableList<Pair<ModuleSources, List<ComponentArtifactMetadata>>>,
        dependencies: Iterable<DependencyMetadata>,
        visited: MutableSet<ComponentSelector> = hashSetOf()
    ) {
        for (dependency in dependencies) {
            val selector = dependency.selector

            if (selector in visited) {
                continue
            }

            visited += selector

            val constraint = if (selector is ModuleComponentSelector) selector.versionConstraint else DefaultImmutableVersionConstraint.of()
            val strictly = if (constraint.strictVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.strictVersion)
            val require = if (constraint.requiredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.requiredVersion)
            val preferred = if (constraint.preferredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.preferredVersion)
            val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))
            val idResult = DefaultBuildableComponentIdResolveResult()

            resolvers.get().componentIdResolver.resolve(dependency, strictly ?: preferred ?: require, reject, idResult)

            val metadata = idResult.metadata ?: run {
                val componentResult = DefaultBuildableComponentResolveResult()
                resolvers.get().componentResolver.resolve(idResult.id, DefaultComponentOverrideMetadata.EMPTY, componentResult)

                componentResult.metadata
            }

            val configurations = dependency.selectVariants(
                (configuration.attributes as AttributeContainerInternal).asImmutable(),
                metadata,
                project.dependencies.attributesSchema as AttributesSchemaInternal,
                configuration.outgoing.capabilities
            )

            for (configuration in configurations) {
                buildClasspath(selectedVariants, configuration.dependencies, visited)
            }

            val artifacts = buildList {
                if (dependency.artifacts.isEmpty()) {
                    for (configuration in configurations) {
                        addAll(configuration.allArtifacts)
                    }
                } else {
                    for (configuration in configurations) {
                        for (artifactName in dependency.artifacts) {
                            add(configuration.artifact(artifactName))
                        }
                    }
                }
            }

            selectedVariants.add((metadata.sources ?: ImmutableModuleSources.of()) to artifacts)
        }
    }

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: RemappedComponentIdentifier) = metadata.copy(
        objects,
        identifier,
        {
            val transitiveDependencies = dependencies
            val sourceNamespace = attributes.findEntry(MappingsNamespace.attribute.name).takeIf(AttributeValue<*>::isPresent)?.get() as? String

            val wrapArtifact = { artifact: ComponentArtifactMetadata ->
                if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                    val selectedArtifacts = mutableListOf<Pair<ModuleSources, List<ComponentArtifactMetadata>>>()

                    buildClasspath(selectedArtifacts, transitiveDependencies)

                    RemappedComponentArtifactMetadata(
                        project,
                        artifact as ModuleComponentArtifactMetadata,
                        identifier,
                        sourceNamespace,
                        selectedArtifacts
                    )
                } else {
                    PassthroughRemappedArtifactMetadata(artifact)
                }
            }

            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
            val libraryElements = attributes.findEntry(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.name)
            if (category.isPresent && libraryElements.isPresent && category.get() == Category.LIBRARY && libraryElements.get() == LibraryElements.JAR) {
                copy(
                    objects,

                    { oldName ->
                        object : DisplayName {
                            override fun getDisplayName() = "remapped ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Remapped ${oldName.capitalizedDisplayName}"
                        }
                    },

                    attributes.addNamed(attributesFactory, instantiator, MappingsNamespace.attribute, identifier.targetNamespace.name),

                    {
                        map { dependency ->
                            val namespace = dependency.selector.attributes.getAttribute(MappingsNamespace.attribute)
                            val selector = DefaultModuleComponentSelector.withAttributes(
                                dependency.selector as ModuleComponentSelector, // Unsafe assumption, should be changed if we want things to be more generic.
                                attributesFactory.concat(
                                    (dependency.selector.attributes as AttributeContainerInternal).asImmutable(),
                                    attributesFactory.of(MappingsNamespace.attribute, identifier.targetNamespace)
                                )
                            )

                            RemappedDependencyMetadataWrapper(
                                dependency,
                                selector,
                                identifier.sourceNamespace ?: namespace,
                                identifier.targetNamespace,
                                identifier.mappingsConfiguration
                            )
                        }
                    },

                    wrapArtifact,

                    {
                        val artifacts = map(wrapArtifact)

                        if (identifier.original is MinecraftComponentIdentifier && identifier.original.isBase) {
                            // Add the mappings file if we're remapping Minecraft.
                            artifacts + MappingsArtifact(project, identifier, identifier.mappingsConfiguration)
                        } else {
                            artifacts
                        }
                    }
                )
            } else {
                this
            }
        }
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is RemappedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)
            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata
                val mappingsConfiguration = dependency.relatedConfiguration ?: MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION

                if (result.id is ModuleComponentIdentifier) {
                    val id = RemappedComponentIdentifier(
                        result.id as ModuleComponentIdentifier,
                        dependency.sourceNamespace,
                        dependency.targetNamespace,
                        mappingsConfiguration,
                        dependency is LocalOriginDependencyMetadata
                    )

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        result.resolved(wrapMetadata(metadata, id))
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is RemappedComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is RemappedComponentIdentifier) {
        MetadataSourcedComponentArtifacts().getArtifactsFor(
            component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact is RemapperArtifact) {
            val mappingFiles = project.configurations.getByName(artifact.mappingsConfiguration)

            val remapper = project.extensions
                .getByType(MinecraftCodevExtension::class.java)
                .extensions
                .getByType(RemapperExtension::class.java)

            val mappings = remapper.loadMappings(mappingFiles, objects, false)

            when (artifact) {
                is RemappedComponentArtifactMetadata -> {
                    val id = artifact.componentId

                    resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

                    if (result.isSuccessful) {
                        val (platforms, versions, namespaces) = project.extensions.getByType(MinecraftCodevExtension::class.java).detectModInfo(result.result.toPath())

                        if (!id.requested && platforms.isEmpty() && versions.isEmpty() && namespaces.isEmpty()) {
                            return
                        }

                        val sourceNamespace = namespaces.firstOrNull { mappings.tree.getNamespaceId(it) != MappingTreeView.NULL_NAMESPACE_ID }
                            ?: id.sourceNamespace?.name
                            ?: artifact.namespace
                            ?: MappingsNamespace.OBF

                        val urlId = RemappedArtifactIdentifier(
                            artifact.id.asSerializable,
                            id.targetNamespace.name,
                            mappings.hash,
                            checksumService.sha1(result.result)
                        )

                        getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                            val classpath = mutableSetOf<File>()

                            val minecraftVersion = versions.firstOrNull()

                            if (minecraftVersion != null) {
                                val extraClasspathDependencies = project
                                    .extension<MinecraftCodevExtension>()
                                    .extension<RemapperExtension>()
                                    .remapClasspathRules
                                    .get()
                                    .flatMap { it(sourceNamespace, id.targetNamespace.name, minecraftVersion, id.mappingsConfiguration) }

                                for (extraClasspathDependency in extraClasspathDependencies) {
                                    val dependency = dependencyDescriptorFactories.get().firstNotNullOfOrNull {
                                        if (it.canConvert(extraClasspathDependency)) {
                                            it.createDependencyMetadata(id, configuration.name, configuration.attributes, extraClasspathDependency)
                                        } else {
                                            null
                                        }
                                    } ?: continue

                                    project.visitDependencyArtifacts(resolvers, configuration, dependency, true) {
                                        classpath.add(it)
                                    }
                                }
                            }

                            for ((sources, artifacts) in artifact.selectedArtifacts) {
                                for (dependencyArtifact in artifacts) {
                                    val artifactResult = DefaultBuildableArtifactResolveResult()
                                    resolvers.get().artifactResolver.resolveArtifact(dependencyArtifact, sources, artifactResult)

                                    if (artifactResult.isSuccessful) {
                                        classpath.add(artifactResult.result)
                                    } else if (!dependencyArtifact.isOptionalArtifact) {
                                        result.failed(result.failure)

                                        return@getOrResolve null
                                    }
                                }
                            }

                            val file = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                                override fun description() = BuildOperationDescriptor
                                    .displayName("Remapping ${result.result} from $sourceNamespace to ${id.targetNamespace}")
                                    .progressDisplayName("Mappings: $mappingFiles")
                                    .metadata(BuildOperationCategory.TASK)

                                override fun call(context: BuildOperationContext) = context.callWithStatus {
                                    JarRemapper.remap(
                                        remapper,
                                        mappings.tree,
                                        sourceNamespace,
                                        id.targetNamespace.name,
                                        result.result.toPath(),
                                        classpath
                                    )
                                }
                            })

                            val output = cacheManager.fileStoreDirectory
                                .resolve(mappings.hash.toString())
                                .resolve(id.group)
                                .resolve(id.module)
                                .resolve(id.version)
                                .resolve(checksumService.sha1(file.toFile()).toString())
                                .resolve("${result.result.nameWithoutExtension}-${id.targetNamespace.name}.${result.result.extension}")

                            output.parent.createDirectories()
                            file.copyTo(output)

                            output.toFile()
                        }
                    }
                }

                is MappingsArtifact -> {
                    val output = cacheManager.fileStoreDirectory
                        .resolve(mappings.hash.toString())
                        .resolve("mappings.${ArtifactTypeDefinition.ZIP_TYPE}")

                    if (output.notExists()) {
                        output.parent.createDirectories()

                        zipFileSystem(output, true).use {
                            val directory = it.base.getPath("mappings")
                            directory.createDirectory()

                            directory.resolve("mappings.tiny").writer().use { writer ->
                                val visitor = MappingNsRenamer(
                                    FieldAddDescVisitor(Tiny2Writer(writer, false)),
                                    /*
                                     * To allow it to be used directly in the Fabric Loader, we rename our 'obf' to what fabric expects, which is 'official'
                                     * This isn't really great design, since the remapper shouldn't cater to something so Fabric specific, but it beats overcomplicating it
                                     */
                                    mapOf(MappingsNamespace.OBF to "official")
                                )

                                mappings.tree.accept(visitor)
                            }
                        }
                    }

                    result.resolved(output.toFile())
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        } else if (artifact is PassthroughRemappedArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.original, moduleSources, result)
        }
    }
}

class RemappedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val sourceNamespace: MappingsNamespace?,
    val targetNamespace: MappingsNamespace,
    val mappingsConfiguration: String,
    val requested: Boolean,
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Remapped to $targetNamespace)"

    override fun toString() = displayName
}
