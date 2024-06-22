package net.msrandom.minecraftcodev.intersection.resolve

import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentMetadataHolder
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.gradle.api.VariantMetadataHolder
import net.msrandom.minecraftcodev.intersection.dependency.DslOriginIntersectionDependencyMetadata
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.UnionVersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSet
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSetFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributeContainerInternal
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultMutableCapabilities
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
import java.io.File
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.extension

open class IntersectionComponentResolvers
@Inject
constructor(
    private val configuration: Configuration,
    private val project: Project,
    private val resolvers: ComponentResolversChainProvider,
    private val versionSelectorSchema: VersionSelectorScheme,
    private val attributesFactory: ImmutableAttributesFactory,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val checksumService: ChecksumService,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val objectFactory: ObjectFactory,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("intersection")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { IntersectionArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this

    override fun getComponentResolver() = this

    override fun getArtifactSelector() = this

    override fun getArtifactResolver() = this

    override fun resolve(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
    ) {
        if (dependency !is DslOriginIntersectionDependencyMetadata) {
            return
        }

        val selector = dependency.selector as ModuleComponentSelector

        val componentIdentifiers =
            dependency.dependencies.map {
                val dependencySelector = it.selector

                val constraint =
                    if (dependencySelector is ModuleComponentSelector) {
                        dependencySelector.versionConstraint
                    } else {
                        DefaultImmutableVersionConstraint.of()
                    }

                val strictly =
                    constraint
                        .strictVersion
                        .takeUnless(String::isEmpty)
                        ?.let(versionSelectorSchema::parseSelector)

                val require =
                    constraint
                        .requiredVersion
                        .takeUnless(String::isEmpty)
                        ?.let(versionSelectorSchema::parseSelector)

                val preferred =
                    constraint
                        .preferredVersion
                        .takeUnless(String::isEmpty)
                        ?.let(versionSelectorSchema::parseSelector)

                val reject = UnionVersionSelector(constraint.rejectedVersions.map(versionSelectorSchema::parseSelector))

                val idResult = DefaultBuildableComponentIdResolveResult()

                resolvers.get().componentIdResolver.resolve(it, strictly ?: preferred ?: require, reject, idResult)

                if (idResult.failure != null) {
                    result.failed(idResult.failure)

                    return
                }

                it to idResult.id as ModuleComponentIdentifier
            }

        val id =
            IntersectionComponentIdentifier(
                DefaultModuleIdentifier.newId(selector.group, selector.module),
                selector.version,
                componentIdentifiers,
            )

        result.resolved(id, DefaultModuleVersionIdentifier.newId(id))
    }

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier !is IntersectionComponentIdentifier) {
            return
        }

        val states =
            identifier.dependencies.map { (dependency, identifier) ->
                val stateResult = DefaultBuildableComponentResolveResult()

                resolvers.get().componentResolver.resolve(
                    identifier,
                    DefaultComponentOverrideMetadata.forDependency(
                        dependency.isChanging,
                        dependency.artifacts.getOrNull(0),
                        DefaultComponentOverrideMetadata.extractClientModule(dependency),
                    ),
                    stateResult,
                )

                if (stateResult.failure != null) {
                    result.failed(stateResult.failure)

                    return
                }

                dependency to stateResult.state
            }

        val variants =
            states.map { (dependency, state) ->
                val variants =
                    synchronized(this) {
                        dependency.selectVariants(
                            (configuration.attributes as AttributeContainerInternal).asImmutable(),
                            state,
                            project.dependencies.attributesSchema as AttributesSchemaInternal,
                            (dependency.selector as ModuleComponentSelector).requestedCapabilities,
                        )
                    }

                // TODO maybe do result.failed directly
                require(variants.variants.size == 1)

                CodevGradleLinkageLoader.wrapVariant(identifier, variants.variants.first())
            }

        val artifacts =
            variants.mapNotNull { variant ->
                variant.artifacts.firstOrNull { it.name.type == ArtifactTypeDefinition.JAR_TYPE }
            }

        val variant =
            VariantMetadataHolder(
                Dependency.DEFAULT_CONFIGURATION,
                identifier,
                object : DisplayName {
                    override fun getDisplayName() = "intersection (${variants.joinToString { it.displayName.displayName }})"

                    override fun getCapitalizedDisplayName() =
                        "Intersection of Dependencies (${variants.joinToString { it.displayName.capitalizedDisplayName }})"
                },
                variants.flatMap(VariantMetadataHolder::dependencies),
                listOf(IntersectionComponentArtifactMetadata(artifacts, identifier, project)),
                variants
                    .map(VariantMetadataHolder::attributes)
                    .reduce { accumulated, attributes -> attributesFactory.concat(attributes, accumulated) },
                DefaultMutableCapabilities(variants.flatMap { it.capabilities.capabilities }),
                setOf(Dependency.DEFAULT_CONFIGURATION),
            )

        val component =
            ComponentMetadataHolder(
                identifier,
                ImmutableAttributes.EMPTY,
                DefaultModuleVersionIdentifier.newId(identifier),
                listOf(variant),
                states.any { (_, state) -> state.metadata.isChanging },
                "intersection",
                listOf("intersection"),
            )

        result.resolved(CodevGradleLinkageLoader.wrapComponentMetadata(component, objectFactory))
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) =
        identifier !is IntersectionComponentIdentifier ||
            identifier.dependencies.all { (_, identifier) ->
                resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)
            }

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ): ArtifactSet? {
        if (component.id !is IntersectionComponentIdentifier) {
            return null
        }

        return ArtifactSetFactory.createFromVariantMetadata(
            component.id,
            allVariants,
            legacyVariants,
            project.dependencies.attributesSchema as AttributesSchemaInternal,
            overriddenAttributes,
        )
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
        if (artifact is PassthroughArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.original, result)
            return
        }

        if (artifact !is IntersectionComponentArtifactMetadata) {
            return
        }

        val files =
            artifact.artifacts.map {
                val artifactResult = DefaultBuildableArtifactResolveResult()

                resolvers.get().artifactResolver.resolveArtifact(component, it, artifactResult)

                if (artifactResult.failure != null) {
                    result.failed(artifactResult.failure)

                    return
                }

                artifactResult.result.file
            }

        val hashCodeBytes =
            files.map { checksumService.sha1(it).toByteArray() }.reduce { accumulated, hashCode ->
                accumulated.zip(hashCode).map { (a, b) -> (b + a * 31).toByte() }.toByteArray()
            }

        val hashCode = HashCode.fromBytes(hashCodeBytes)

        val urlId = IntersectionArtifactIdentifier(hashCode)

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
                                .displayName("Intersection of $files")
                                .progressDisplayName("Creating intersection of Jars")
                                .metadata(BuildOperationCategory.TASK)

                        override fun call(context: BuildOperationContext) =
                            context.callWithStatus {
                                files
                                    .map(File::toPath)
                                    .reduce(JarIntersection::intersection)
                            }
                    },
                )

            val output =
                cacheManager.fileStoreDirectory
                    .resolve(checksumService.sha1(file.toFile()).toString())
                    .resolve("${artifact.componentId.module}-${artifact.componentId.version}-intersection.${file.extension}")

            output.parent.createDirectories()
            file.copyTo(output)

            output.toFile()
        }
    }
}

class IntersectionComponentIdentifier(
    private val moduleIdentifier: ModuleIdentifier,
    private val version: String,
    val dependencies: List<Pair<LocalOriginDependencyMetadata, ModuleComponentIdentifier>>,
) : ModuleComponentIdentifier {
    override fun getDisplayName() =
        "Intersection of dependencies (${dependencies.joinToString { (_, identifier) -> identifier.displayName }})"

    override fun getGroup(): String = moduleIdentifier.group

    override fun getModule(): String = moduleIdentifier.name

    override fun getVersion() = version

    override fun getModuleIdentifier() = moduleIdentifier

    override fun equals(other: Any?) =
        other is IntersectionComponentIdentifier &&
            moduleIdentifier == other.moduleIdentifier &&
            version == other.version &&
            dependencies.map { (_, identifier) -> identifier } == other.dependencies.map { (_, identifier) -> identifier }

    override fun hashCode(): Int {
        val dependencyHashCode = dependencies.map { (_, identifier) -> identifier }.hashCode()

        return dependencyHashCode + moduleIdentifier.hashCode() * 961 + version.hashCode() * 31
    }
}
