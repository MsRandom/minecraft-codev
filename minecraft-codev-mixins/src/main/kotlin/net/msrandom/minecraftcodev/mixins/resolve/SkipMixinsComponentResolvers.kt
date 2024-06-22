package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.createDeterministicCopy
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.mixins.MixinsExtension
import net.msrandom.minecraftcodev.mixins.dependency.SkipMixinDependencyMetadata
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
import org.gradle.internal.resolve.ArtifactResolveException
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
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

open class SkipMixinsComponentResolvers
@Inject
constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val checksumService: ChecksumService,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val objects: ObjectFactory,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("mixins/skip-mixins")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), { SkipMixinsArtifactIdentifier.ArtifactSerializer }) {
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
        if (dependency is SkipMixinDependencyMetadata) {
            resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult() && result.failure == null) {
                val metadata = result.state

                if (result.id is ModuleComponentIdentifier) {
                    val id = SkipMixinsComponentIdentifier(result.id as ModuleComponentIdentifier)

                    if (metadata == null) {
                        result.resolved(id, result.moduleVersionId)
                    } else {
                        result.resolved(
                            CodevGradleLinkageLoader.wrapComponentMetadata(
                                metadata,
                                SkipMixinsComponentMetadataDelegate(id),
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
        if (identifier is SkipMixinsComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(
                    CodevGradleLinkageLoader.wrapComponentMetadata(
                        result.state,
                        SkipMixinsComponentMetadataDelegate(identifier),
                        resolvers,
                        objects,
                    ),
                )
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) =
        identifier is SkipMixinsComponentIdentifier && resolvers.get().componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is SkipMixinsComponentIdentifier) {
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
        if (artifact is SkipMixinsComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

            if (result.hasResult() && result.failure == null) {
                val base = result.result

                val urlId =
                    SkipMixinsArtifactIdentifier(
                        artifact.id.asSerializable,
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
                    val rules =
                        project.extensions
                            .getByType(MinecraftCodevExtension::class.java).extensions
                            .getByType(MixinsExtension::class.java)
                            .rules

                    buildOperationExecutor.call(
                        object : CallableBuildOperation<File?> {
                            override fun description() =
                                BuildOperationDescriptor
                                    .displayName("Removing ${result.result} mixins")
                                    .progressDisplayName("Stripping Mixins")
                                    .metadata(BuildOperationCategory.TASK)

                            @Suppress("WRONG_NULLABILITY_FOR_JAVA_OVERRIDE")
                            override fun call(context: BuildOperationContext) =
                                context.callWithStatus {
                                    val handler =
                                        zipFileSystem(base.file.toPath()).use {
                                            val path = it.base.getPath("/")

                                            rules.get().firstNotNullOfOrNull { rule ->
                                                rule.load(path)
                                            }
                                        }

                                    if (handler == null) {
                                        result.failed(
                                            ArtifactResolveException(
                                                artifact.id,
                                                UnsupportedOperationException(
                                                    "Couldn't find mixin configs for $base, unsupported format.\n" +
                                                        "You can register new mixin loading rules with minecraft.mixins.rules",
                                                ),
                                            ),
                                        )

                                        null
                                    } else {
                                        val id = artifact.componentId
                                        val file = base.file.toPath().createDeterministicCopy("mixins-skipped", ".tmp.jar")

                                        zipFileSystem(file).use {
                                            val root = it.base.getPath("/")
                                            handler.list(root).forEach { path -> root.resolve(path).deleteExisting() }
                                            handler.remove(root)
                                        }

                                        val output =
                                            cacheManager.fileStoreDirectory
                                                .resolve(id.group)
                                                .resolve(id.module)
                                                .resolve(id.version)
                                                .resolve(checksumService.sha1(file.toFile()).toString())
                                                .resolve("${base.file.nameWithoutExtension}-mixins-skipped.${base.file.extension}")

                                        output.parent.createDirectories()
                                        file.copyTo(output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

                                        output.toFile()
                                    }
                                }
                        },
                    )
                }
            }
        }
    }
}

data class SkipMixinsComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Mixins Stripped)"

    override fun toString() = displayName
}
