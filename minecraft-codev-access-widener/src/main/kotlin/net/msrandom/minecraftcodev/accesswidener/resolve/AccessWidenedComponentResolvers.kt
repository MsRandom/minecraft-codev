package net.msrandom.minecraftcodev.accesswidener.resolve

import net.msrandom.minecraftcodev.accesswidener.AccessWidenerExtension
import net.msrandom.minecraftcodev.accesswidener.JarAccessWidener
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.accesswidener.dependency.AccessWidenedDependencyMetadata
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSetFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
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
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class AccessWidenedComponentResolvers
@Inject
constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val buildOperationExecutor: BuildOperationExecutor,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("access-widened")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { AccessWidenedArtifactIdentifier.ArtifactSerializer }) {
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
        if (dependency is AccessWidenedDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.state
                val accessWidenersConfiguration =
                    dependency.relatedConfiguration.takeUnless(
                        String::isEmpty,
                    ) ?: MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION

                if (result.id is ModuleComponentIdentifier) {
                    val id = AccessWidenedComponentIdentifier(result.id as ModuleComponentIdentifier, accessWidenersConfiguration)

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        result.resolved(
                            CodevGradleLinkageLoader.wrapComponentMetadata(
                                metadata,
                                AccessWidenedComponentMetadataDelegate(id, project),
                                resolvers,
                                objects,
                            ),
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
        if (identifier is AccessWidenedComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(
                    CodevGradleLinkageLoader.wrapComponentMetadata(
                        result.state,
                        AccessWidenedComponentMetadataDelegate(identifier, project),
                        resolvers,
                        objects,
                    ),
                )
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) =
        identifier is AccessWidenedComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is AccessWidenedComponentIdentifier) {
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
        if (artifact is AccessWidenedComponentArtifactMetadata) {
            val id = artifact.componentId
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

            if (result.isSuccessful) {
                val base = result.result

                val (platforms, versions, namespaces) =
                    project.extensions.getByType(
                        MinecraftCodevExtension::class.java,
                    ).detectModInfo(base.file.toPath())

                if (platforms.isEmpty() && versions.isEmpty() && namespaces.isEmpty()) {
                    return
                }

                val namespace = namespaces.firstOrNull() ?: artifact.namespace

                val accessWideners = project.configurations.getByName(id.accessWidenersConfiguration)

                val accessWidener =
                    project.extensions
                        .getByType(MinecraftCodevExtension::class.java)
                        .extensions
                        .getByType(AccessWidenerExtension::class.java)
                        .loadAccessWideners(accessWideners, objects, namespace.takeUnless(String::isEmpty))

                val urlId =
                    AccessWidenedArtifactIdentifier(
                        artifact.id.asSerializable,
                        accessWidener.hash,
                        checksumService.sha1(base.file),
                    )

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
                                        .displayName("Access Widening $base")
                                        .metadata(BuildOperationCategory.TASK)

                                override fun call(context: BuildOperationContext) =
                                    context.callWithStatus {
                                        JarAccessWidener.accessWiden(accessWidener.tree, base.file.toPath())
                                    }
                            },
                        )

                    val output =
                        cacheManager.fileStoreDirectory
                            .resolve(accessWidener.hash.toString())
                            .resolve(id.group)
                            .resolve(id.module)
                            .resolve(id.version)
                            .resolve(checksumService.sha1(file.toFile()).toString())
                            .resolve("${base.file.nameWithoutExtension}-access-widened.${base.file.extension}")

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

data class AccessWidenedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val accessWidenersConfiguration: String,
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Access Widened)"

    override fun toString() = displayName
}
