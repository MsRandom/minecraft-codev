package net.msrandom.minecraftcodev.accesswidener.resolve.intersection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import net.msrandom.minecraftcodev.accesswidener.AccessWidenerExtension
import net.msrandom.minecraftcodev.accesswidener.dependency.intersection.DslOriginAccessModifierIntersectionDependencyMetadata
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.utils.*
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionSelector
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.outputStream

open class AccessModifierIntersectionComponentResolvers @Inject constructor(
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
    private val cacheManager = cacheProvider.manager("access-modifier-intersections")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), ::artifactIdSerializer) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is DslOriginAccessModifierIntersectionDependencyMetadata) {
            val identifiers = mutableListOf<Pair<LocalOriginDependencyMetadata, ModuleComponentIdentifier>>()

            for (subDependency in dependency.dependencies) {
                val selector = subDependency.selector
                val constraint = if (selector is ModuleComponentSelector) selector.versionConstraint else DefaultImmutableVersionConstraint.of()
                val strictly = if (constraint.strictVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.strictVersion)
                val require = if (constraint.requiredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.requiredVersion)
                val preferred = if (constraint.preferredVersion.isEmpty()) null else versionSelectorSchema.parseSelector(constraint.preferredVersion)
                val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))
                val idResult = DefaultBuildableComponentIdResolveResult()

                resolvers.get().componentIdResolver.resolve(subDependency, strictly ?: preferred ?: require, reject, idResult)

                if (idResult.failure != null) {
                    result.failed(idResult.failure)

                    return
                }

                identifiers.add(subDependency to idResult.id as ModuleComponentIdentifier)
            }

            val module = (dependency.source as ModuleVersionSelector).module

            result.resolved(AccessModifierIntersectionComponentIdentifier(module, identifiers), DefaultModuleVersionIdentifier.newId(module, ""))
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is AccessModifierIntersectionComponentIdentifier) {
            val metadata = mutableListOf<ModuleComponentResolveMetadata>()

            for ((subDependency, subIdentifier) in identifier.identifiers) {
                val metadataResult = DefaultBuildableComponentResolveResult()
                resolvers.get().componentResolver.resolve(
                    subIdentifier,
                    DefaultComponentOverrideMetadata.forDependency(subDependency.isChanging, subDependency.artifacts.getOrNull(0), DefaultComponentOverrideMetadata.extractClientModule(subDependency)),
                    metadataResult
                )

                if (metadataResult.failure != null) {
                    result.failed(metadataResult.failure)

                    return
                }

                metadata.add(metadataResult.metadata as ModuleComponentResolveMetadata)
            }

            val moduleVersionId = DefaultModuleVersionIdentifier.newId("", identifier.module, "")

            result.resolved(
                CodevGradleLinkageLoader.ComponentResolveMetadata(
                    objects,
                    ImmutableAttributes.EMPTY,
                    identifier,
                    moduleVersionId,
                    listOf(
                        CodevGradleLinkageLoader.ConfigurationMetadata(
                            objects,
                            Dependency.DEFAULT_CONFIGURATION,
                            identifier,
                            listOf(),
                            listOf(
                                AccessModifierIntersectionComponentArtifactMetadata(
                                    project,
                                    metadata.map {
                                        val artifacts = mutableListOf<Pair<ModuleSources, ComponentArtifactMetadata>>()
                                        artifacts
                                    },
                                    identifier,
                                    moduleVersionId,
                                )
                            ),
                            ImmutableAttributes.EMPTY,
                            ImmutableCapabilities.EMPTY,
                            setOf(Dependency.DEFAULT_CONFIGURATION)
                        )
                    ),
                    false,
                    "release",
                    listOf("release")
                )
            )
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is AccessModifierIntersectionComponentIdentifier && identifier.identifiers.all { (_, id) ->
        resolvers.get().componentResolver.isFetchingMetadataCheap(id)
    }

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is AccessModifierIntersectionComponentIdentifier) {
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
        if (artifact is AccessModifierIntersectionComponentArtifactMetadata) {
            val id = artifact.componentId

            if (result.isSuccessful) {
                val accessWideners = project.extensions
                    .getByType(MinecraftCodevExtension::class.java)
                    .extensions
                    .getByType(AccessWidenerExtension::class.java)

                val accessWidener = artifact.selectedArtifacts.map {
                    val files = it.map { (sources, accessWidenerArtifact) ->
                        val artifactResult = DefaultBuildableArtifactResolveResult()

                        resolvers.get().artifactResolver.resolveArtifact(accessWidenerArtifact, sources, artifactResult)

                        if (!artifactResult.hasResult() && !accessWidenerArtifact.isOptionalArtifact) {
                            result.failed(artifactResult.failure)

                            return
                        }

                        artifactResult.result
                    }

                    accessWideners.loadAccessWideners(files, objects)
                }.reduce { a, b ->
                    AccessWidenerExtension.LoadedAccessWideners(
                        a.tree.intersection(b.tree),
                        HashCode.fromBytes(
                            a.hash.toByteArray().zip(b.hash.toByteArray()).map { (byteA, byteB) ->
                                (byteA * 31 + byteB).toByte()
                            }.toByteArray()
                        )
                    )
                }

                val urlId = DefaultModuleComponentArtifactIdentifier(
                    DefaultModuleComponentIdentifier(id.moduleIdentifier, accessWidener.hash.toString()),
                    artifact.name.name,
                    artifact.name.type,
                    artifact.name.extension,
                    artifact.name.classifier,
                )

                getOrResolve(artifact, urlId, artifactCache, cachePolicy, timeProvider, result) {
                    val output = cacheManager.fileStoreDirectory.resolve(accessWidener.hash.toString())

                    output.outputStream().use {
                        Json.encodeToStream(accessWidener.tree, it)
                    }

                    output.toFile()
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        }
    }
}

class AccessModifierIntersectionComponentIdentifier(
    private val module: ModuleIdentifier,
    val identifiers: List<Pair<LocalOriginDependencyMetadata, ModuleComponentIdentifier>>,
) : ModuleComponentIdentifier {
    override fun getDisplayName() = "Access widener intersection"
    override fun getGroup(): String = module.group ?: ""
    override fun getModule(): String = module.name
    override fun getVersion() = ""
    override fun getModuleIdentifier() = module
    override fun toString() = displayName
}
