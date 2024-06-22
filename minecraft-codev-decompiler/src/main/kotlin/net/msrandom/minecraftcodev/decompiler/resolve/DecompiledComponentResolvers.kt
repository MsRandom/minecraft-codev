package net.msrandom.minecraftcodev.decompiler.resolve

import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.visitDependencyResultArtifacts
import net.msrandom.minecraftcodev.decompiler.SourcesGenerator
import net.msrandom.minecraftcodev.decompiler.dependency.DecompiledDependencyMetadata
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.internal.artifacts.ResolverResults
import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.*
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.transform.VariantSelector
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import java.nio.file.Path
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class DecompiledComponentResolvers
@Inject
constructor(
    private val configuration: Configuration,
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val attributesFactory: ImmutableAttributesFactory,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("generated-sources")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { DecompiledArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this

    override fun getComponentResolver() = this

    override fun getArtifactSelector() = this

    override fun getArtifactResolver() = this

    private fun metadataDelegate(identifier: DecompiledComponentIdentifier) =
        objects.newInstance(
            DecompiledComponentMetadataDelegate::class.java,
            identifier,
        )

    override fun resolve(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
    ) {
        if (dependency is DecompiledDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.state

                if (result.id is ModuleComponentIdentifier) {
                    val id = DecompiledComponentIdentifier(result.id as ModuleComponentIdentifier)

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        result.resolved(
                            CodevGradleLinkageLoader.wrapComponentMetadata(metadata, metadataDelegate(id), resolvers, objects),
                        )
                    }
                }
            }
        }
    }

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier is DecompiledComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(
                    CodevGradleLinkageLoader.wrapComponentMetadata(result.state, metadataDelegate(identifier), resolvers, objects),
                )
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is DecompiledComponentIdentifier) {
        ArtifactSetFactory.createFromVariantMetadata(
            component.id,
            allVariants,
            legacyVariants,
            attributesSchema,
            overriddenAttributes,
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(
        component: ComponentArtifactResolveMetadata,
        artifactType: ArtifactType,
        result: BuildableArtifactSetResolveResult,
    ) {
        if (artifactType != ArtifactType.SOURCES) {
            return
        }

    /*        val sourcesConfiguration = AttributeConfigurationSelector.selectVariantsUsingAttributeMatching(
                ImmutableAttributes.EMPTY
                    .addNamed(attributesFactory, instantiator, Category.CATEGORY_ATTRIBUTE, Category.DOCUMENTATION)
                    .addNamed(attributesFactory, instantiator, DocsType.DOCS_TYPE_ATTRIBUTE, DocsType.SOURCES),
                emptyList(),
                component,
                project.dependencies.attributesSchema as AttributesSchemaInternal,
                emptyList()
            )

            if (sourcesConfiguration.variants.isEmpty()) {
                return
            }

            require(sourcesConfiguration.variants.size == 1)

            val variant = sourcesConfiguration.variants.first()

            result.resolved(variant.)*/
    }

    override fun resolveArtifact(
        component: ComponentArtifactResolveMetadata,
        artifact: ComponentArtifactMetadata,
        result: BuildableArtifactResolveResult,
    ) {
        if (artifact is DecompiledComponentArtifactMetadata) {
            val id = artifact.componentId

            resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

            if (result.isSuccessful) {
                val base = result.result

                val urlId =
                    DecompiledArtifactIdentifier(
                        artifact.id.asSerializable,
                        checksumService.sha1(base.file),
                    )

                val classpath = mutableSetOf<Supplier<File>>()

                val getResultsForBuildDependencies =
                    DefaultConfiguration::class.java.getDeclaredMethod(
                        "getResultsForBuildDependencies",
                    ).apply {
                        isAccessible = true
                    }
                val resolverResults = getResultsForBuildDependencies(configuration) as ResolverResults
                val artifactResults =
                    DefaultLenientConfiguration::class.java.getDeclaredField("artifactResults").apply {
                        isAccessible = true
                    }[resolverResults.visitedArtifacts] as VisitedArtifactsResults

                val moduleId = DefaultModuleComponentIdentifier.newId(id.moduleIdentifier, id.version)

                val resolvedComponentResult =
                    resolverResults.resolutionResult.allComponents.first {
                        it.id == moduleId
                    }

                fun handleDependencies(
                    dependencies: Collection<DependencyResult>,
                    out: MutableSet<ComponentIdentifier>,
                ) {
                    for (dependency in dependencies.filterIsInstance<ResolvedDependencyResult>()) {
                        out.add(dependency.selected.id)

                        handleDependencies(dependency.selected.getDependenciesForVariant(dependency.resolvedVariant), out)
                    }
                }

                val dependencies =
                    buildSet {
                        handleDependencies(resolvedComponentResult.dependencies, this)
                    }

                val artifacts =
                    artifactResults.selectLenient(
                        {
                            it is ModuleComponentIdentifier && DefaultModuleComponentIdentifier.newId(
                                it.moduleIdentifier,
                                it.version,
                            ) in dependencies
                        },
                        object : VariantSelector {
                            override fun getRequestedAttributes() =
                                (configuration.incoming.attributes as AttributeContainerInternal).asImmutable()

                            override fun select(
                                candidates: ResolvedVariantSet,
                                factory: VariantSelector.Factory,
                            ): ResolvedArtifactSet {
                                val matcher =
                                    (project.dependencies.attributesSchema as AttributesSchemaInternal).withProducer(
                                        candidates.schema,
                                    )
                                val attributes = attributesFactory.concat(requestedAttributes, candidates.overriddenAttributes)
                                val explanationBuilder = AttributeMatchingExplanationBuilder.logging()

                                val match = matcher.matches(candidates.variants, attributes, explanationBuilder)

                                require(match.size == 1)

                                return match.first().artifacts
                            }
                        },
                    )

                visitDependencyResultArtifacts(artifacts, classpath::add)

                getOrResolve(
                    component,
                    artifact,
                    calculatedValueContainerFactory,
                    urlId,
                    artifactCache,
                    cachePolicy,
                    timeProvider,
                    result,
                ) {
                    val file =
                        buildOperationExecutor.call(
                            object : CallableBuildOperation<Path> {
                                override fun description() =
                                    BuildOperationDescriptor
                                        .displayName("Decompiling ${result.result}")
                                        .progressDisplayName("Generating Sources")
                                        .metadata(BuildOperationCategory.TASK)

                                override fun call(context: BuildOperationContext) =
                                    context.callWithStatus {
                                        SourcesGenerator.decompile(base.file.toPath(), classpath.map { it.get().toPath() })
                                    }
                            },
                        )

                    val output =
                        cacheManager.fileStoreDirectory
                            .resolve(id.group)
                            .resolve(id.module)
                            .resolve(id.version)
                            .resolve(checksumService.sha1(file.toFile()).toString())
                            .resolve("${base.file.nameWithoutExtension}-sources.${base.file.extension}")

                    output.parent.createDirectories()
                    file.copyTo(output)

                    output.toFile()
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        } else if (artifact is PassthroughArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.original, result)
        }
    }
}

data class DecompiledComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Decompiled Sources)"

    override fun toString() = displayName
}
