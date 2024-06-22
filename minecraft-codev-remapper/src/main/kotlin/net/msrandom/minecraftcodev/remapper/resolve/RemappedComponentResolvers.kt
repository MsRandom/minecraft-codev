package net.msrandom.minecraftcodev.remapper.resolve

import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.format.Tiny2Writer
import net.fabricmc.mappingio.tree.MappingTreeView
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.IvyDependencyDescriptorFactoriesProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.remapper.FieldAddDescVisitor
import net.msrandom.minecraftcodev.remapper.JarRemapper
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.RemapperExtension
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependencyMetadata
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.DependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultResolvableArtifact
import org.gradle.api.internal.artifacts.ResolverResults
import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.*
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.transform.VariantSelector
import org.gradle.api.internal.attributes.*
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.Describables
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
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.io.path.*

open class RemappedComponentResolvers
@Inject
constructor(
    private val configuration: Configuration,
    private val resolvers: ComponentResolversChainProvider,
    private val dependencyDescriptorFactories: IvyDependencyDescriptorFactoriesProvider,
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

    private fun metadataDelegate(identifier: RemappedComponentIdentifier) =
        objects.newInstance(RemappedComponentMetadataDelegate::class.java, identifier)

    override fun resolve(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
    ) {
        if (dependency is RemappedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)
            if (result.hasResult() && result.failure == null) {
                val metadata = result.state
                val mappingsConfiguration =
                    dependency.relatedConfiguration.takeUnless(
                        String::isEmpty,
                    ) ?: MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION

                if (result.id is ModuleComponentIdentifier) {
                    val id =
                        RemappedComponentIdentifier(
                            result.id as ModuleComponentIdentifier,
                            dependency.sourceNamespace,
                            dependency.targetNamespace,
                            mappingsConfiguration,
                            dependency is LocalOriginDependencyMetadata,
                        )

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
        if (identifier is RemappedComponentIdentifier) {
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
    ) = if (component.id is RemappedComponentIdentifier) {
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
    }

    override fun resolveArtifact(
        component: ComponentArtifactResolveMetadata,
        artifact: ComponentArtifactMetadata,
        result: BuildableArtifactResolveResult,
    ) {
        if (artifact is RemapperArtifact) {
            val mappingFiles = project.configurations.getByName(artifact.mappingsConfiguration)

            val remapper =
                project.extensions
                    .getByType(MinecraftCodevExtension::class.java)
                    .extensions
                    .getByType(RemapperExtension::class.java)

            val mappings = remapper.loadMappings(mappingFiles, objects, false)

            when (artifact) {
                is RemappedComponentArtifactMetadata -> {
                    val componentId = component.id as ModuleComponentIdentifier
                    val artifactId = artifact.componentId

                    resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

                    if (mappings.tree.dstNamespaces == null) {
                        // No mappings
                        return
                    }

                    if (result.isSuccessful) {
                        val (platforms, versions, namespaces) =
                            project.extensions.getByType(
                                MinecraftCodevExtension::class.java,
                            ).detectModInfo(result.result.file.toPath())

                        if (!artifactId.requested && platforms.isEmpty() && versions.isEmpty() && namespaces.isEmpty()) {
                            return
                        }

                        val sourceNamespace =
                            namespaces
                                .firstOrNull { mappings.tree.getNamespaceId(it) != MappingTreeView.NULL_NAMESPACE_ID }
                                ?: artifactId.sourceNamespace.takeUnless(String::isEmpty)
                                ?: artifact.namespace.takeUnless(String::isEmpty)
                                ?: MappingsNamespace.OBF

                        val base = result.result

                        val urlId =
                            RemappedArtifactIdentifier(
                                artifact.id.asSerializable,
                                artifactId.targetNamespace,
                                mappings.hash,
                                checksumService.sha1(base.file),
                            )

                        val classpath = mutableSetOf<Supplier<File>>()

                        val minecraftVersion = versions.firstOrNull()

                        if (minecraftVersion != null) {
                            val extraClasspathDependencies =
                                project
                                    .extension<MinecraftCodevExtension>()
                                    .extension<RemapperExtension>()
                                    .remapClasspathRules
                                    .get()
                                    .flatMap {
                                        it(
                                            sourceNamespace,
                                            artifactId.targetNamespace,
                                            minecraftVersion,
                                            artifactId.mappingsConfiguration,
                                        )
                                    }

                            for (extraClasspathDependency in extraClasspathDependencies) {
                                val dependency =
                                    dependencyDescriptorFactories.get().firstNotNullOfOrNull {
                                        if (it.canConvert(extraClasspathDependency)) {
                                            it.createDependencyMetadata(
                                                artifactId,
                                                configuration.name,
                                                configuration.attributes,
                                                extraClasspathDependency,
                                            )
                                        } else {
                                            null
                                        }
                                    } ?: continue

                                project.visitDependencyArtifacts(resolvers, configuration, dependency, true, visit = classpath::add)
                            }
                        }

                        // If we're here, the configuration graph is resolved, so we get it to get the dependencies of this component
                        //  TODO One problem here is it relies on the real configuration
                        //   So, removing transitive dependencies can break the output. And adding dependencies may change it, which is not ideal.
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

                        val moduleId =
                            DefaultModuleComponentIdentifier.newId(
                                componentId.moduleIdentifier,
                                componentId.version,
                            ).toString()

                        val resolvedComponentResult =
                            resolverResults.resolutionResult.allComponents.first {
                                it.id.toString() == moduleId
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
                                        val attributes =
                                            attributesFactory.concat(
                                                requestedAttributes,
                                                candidates.overriddenAttributes,
                                            )
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
                            try {
                                val file =
                                    buildOperationExecutor.call(
                                        object : CallableBuildOperation<Path> {
                                            override fun description() =
                                                BuildOperationDescriptor
                                                    .displayName(
                                                        "Remapping $base from $sourceNamespace to ${artifactId.targetNamespace}",
                                                    )
                                                    .progressDisplayName("Mappings: $mappingFiles")
                                                    .metadata(BuildOperationCategory.TASK)

                                            override fun call(context: BuildOperationContext) =
                                                context.callWithStatus {
                                                    JarRemapper.remap(
                                                        remapper,
                                                        mappings.tree,
                                                        sourceNamespace,
                                                        artifactId.targetNamespace,
                                                        base.file.toPath(),
                                                        classpath.map(Supplier<File>::get),
                                                    )
                                                }
                                        },
                                    )

                                val output =
                                    cacheManager.fileStoreDirectory
                                        .resolve(mappings.hash.toString())
                                        .resolve(artifactId.group)
                                        .resolve(artifactId.module)
                                        .resolve(artifactId.version)
                                        .resolve(checksumService.sha1(file.toFile()).toString())
                                        .resolve(
                                            "${base.file.nameWithoutExtension}-${artifactId.targetNamespace}.${base.file.extension}",
                                        )

                                output.parent.createDirectories()
                                file.copyTo(output)

                                output.toFile()
                            } catch (e: Exception) {
                                project.logger.error(e.toString())
                                e.printStackTrace()
                                throw e
                            }
                        }
                    }
                }

                is MappingsArtifact -> {
                    val output =
                        cacheManager.fileStoreDirectory
                            .resolve(mappings.hash.toString())
                            .resolve("mappings.${ArtifactTypeDefinition.ZIP_TYPE}")

                    if (output.notExists()) {
                        output.parent.createDirectories()

                        zipFileSystem(output, true).use {
                            val directory = it.base.getPath("mappings")
                            directory.createDirectory()

                            directory.resolve("mappings.tiny").writer().use { writer ->
                                val visitor =
                                    MappingNsRenamer(
                                        FieldAddDescVisitor(Tiny2Writer(writer, false)),
                                        /*
                                         * To allow it to be used directly in the Fabric Loader, we rename our 'obf' to what fabric expects, which is 'official'
                                         * This isn't really great design, since the remapper shouldn't cater to something so Fabric specific, but it beats overcomplicating it
                                         */
                                        mapOf(MappingsNamespace.OBF to "official"),
                                    )

                                mappings.tree.accept(visitor)
                            }
                        }
                    }

                    result.resolved(
                        DefaultResolvableArtifact(
                            component.moduleVersionId,
                            artifact.name,
                            artifact.id,
                            artifact.buildDependencies,
                            calculatedValueContainerFactory.create(Describables.of(artifact.id), output::toFile),
                            calculatedValueContainerFactory,
                        ),
                    )
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

data class RemappedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val sourceNamespace: String,
    val targetNamespace: String,
    val mappingsConfiguration: String,
    val requested: Boolean,
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Remapped to $targetNamespace)"

    override fun toString() = displayName
}
