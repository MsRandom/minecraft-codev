package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.dependency.DecompiledDependencyMetadata
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.utils.SourcesGenerator
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.allArtifacts
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.DocsType
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
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
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class DecompiledComponentResolvers @Inject constructor(
    private val configuration: Configuration,
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val versionSelectorSchema: VersionSelectorScheme,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("remapped")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { DecompiledArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun buildClasspath(selectedVariants: MutableList<Pair<ModuleSources, List<ComponentArtifactMetadata>>>, dependencies: Iterable<DependencyMetadata>) {
        for (dependency in dependencies) {
            val selector = dependency.selector
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

            val configurations = dependency.selectConfigurations(
                (configuration.attributes as AttributeContainerInternal).asImmutable(),
                metadata,
                project.dependencies.attributesSchema as AttributesSchemaInternal,
                configuration.outgoing.capabilities
            )

            for (configuration in configurations) {
                buildClasspath(selectedVariants, configuration.dependencies)
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

    private fun wrapMetadata(metadata: ComponentResolveMetadata, identifier: DecompiledComponentIdentifier) = metadata.copy(
        identifier,
        {
            val transitiveDependencies = dependencies

            val category = attributes.findEntry(Category.CATEGORY_ATTRIBUTE.name)
            val docsType = attributes.findEntry(DocsType.DOCS_TYPE_ATTRIBUTE.name)
            if (category.isPresent && docsType.isPresent && category.get() == Category.DOCUMENTATION && docsType.get() == DocsType.SOURCES) {
                copy(
                    { oldName ->
                        object : DisplayName {
                            override fun getDisplayName() = "decompiled ${oldName.displayName}"
                            override fun getCapitalizedDisplayName() = "Decompiled ${oldName.capitalizedDisplayName}"
                        }
                    },
                    attributes,
                    { dependency -> dependency },
                    { artifact, _ ->
                        if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                            val selectedArtifacts = mutableListOf<Pair<ModuleSources, List<ComponentArtifactMetadata>>>()

                            buildClasspath(selectedArtifacts, transitiveDependencies)

                            DecompiledComponentArtifactMetadata(
                                artifact as ModuleComponentArtifactMetadata,
                                identifier,
                                selectedArtifacts
                            )
                        } else {
                            PassthroughDecompiledArtifactMetadata(artifact)
                        }
                    },
                    emptyList(),
                    objects
                )
            } else {
                this
            }
        },
        { artifact ->
            if (artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                DecompiledComponentArtifactMetadata(artifact, identifier, emptyList())
            } else {
                artifact
            }
        },
        objects
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is DecompiledDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.metadata

                if (result.id is ModuleComponentIdentifier) {
                    val id = DecompiledComponentIdentifier(result.id as ModuleComponentIdentifier)

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
        if (identifier is DecompiledComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is DecompiledComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is DecompiledComponentIdentifier) {
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
        if (artifact is DecompiledComponentArtifactMetadata) {
            val id = artifact.componentId

            resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

            if (result.isSuccessful) {
                val urlId = DecompiledArtifactIdentifier(
                    artifact.id.asSerializable,
                    checksumService.sha1(result.result)
                )

                getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                    val classpath = mutableSetOf<File>()

                    for ((sources, artifacts) in artifact.selectedArtifacts) {
                        for (dependencyArtifact in artifacts) {
                            val artifactResult = DefaultBuildableArtifactResolveResult()
                            resolvers.get().artifactResolver.resolveArtifact(dependencyArtifact, sources, artifactResult)

                            classpath.add(artifactResult.result)
                        }
                    }

                    val file = buildOperationExecutor.call(object : CallableBuildOperation<Path> {
                        override fun description() = BuildOperationDescriptor
                            .displayName("Decompiling ${result.result}")
                            .progressDisplayName("Generating Sources")
                            .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) = context.callWithStatus {
                            SourcesGenerator.decompile(result.result.toPath(), classpath.map(File::toPath))
                        }
                    })

                    val output = cacheManager.fileStoreDirectory
                        .resolve(id.group)
                        .resolve(id.module)
                        .resolve(id.version)
                        .resolve(checksumService.sha1(file.toFile()).toString())
                        .resolve("${result.result.nameWithoutExtension}-sources.${result.result.extension}")

                    output.parent.createDirectories()
                    file.copyTo(output)

                    output.toFile()
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        } else if (artifact is PassthroughDecompiledArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(artifact.original, moduleSources, result)
        }
    }
}

class DecompiledComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Decompiled Sources)"

    override fun toString() = displayName
}
