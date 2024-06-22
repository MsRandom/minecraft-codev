package net.msrandom.minecraftcodev.includes.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.PassthroughArtifactMetadata
import net.msrandom.minecraftcodev.core.utils.asSerializable
import net.msrandom.minecraftcodev.core.utils.callWithStatus
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import net.msrandom.minecraftcodev.gradle.api.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.includes.IncludesExtension
import net.msrandom.minecraftcodev.includes.dependency.ExtractIncludesDependencyMetadata
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
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.operations.*
import org.gradle.internal.resolve.ModuleVersionResolveException
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resolve.result.BuildableComponentIdResolveResult
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting

open class ExtractIncludesComponentResolvers
@Inject
constructor(
    private val resolvers: ComponentResolversChainProvider,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val attributesSchema: AttributesSchemaInternal,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("includes-extracted")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), MinecraftArtifactResolver.Companion::artifactIdSerializer) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun getComponentIdResolver() = this

    override fun getComponentResolver() = this

    override fun getArtifactSelector() = this

    override fun getArtifactResolver() = this

    private fun metadataDelegate(
        identifier: ExtractIncludesComponentIdentifier,
        overrideArtifact: IvyArtifactName?,
    ) = ExtractIncludesComponentMetadataDelegate(identifier, overrideArtifact, project)

    override fun resolve(
        dependency: DependencyMetadata,
        acceptor: VersionSelector?,
        rejector: VersionSelector?,
        result: BuildableComponentIdResolveResult,
    ) {
        if (dependency !is ExtractIncludesDependencyMetadata) return

        resolvers.get().componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

        if (!result.hasResult() || result.failure != null) return

        val metadata = result.state
        if (result.id !is ModuleComponentIdentifier) return

        val id = ExtractIncludesComponentIdentifier(result.id as ModuleComponentIdentifier)

        if (metadata == null) {
            result.resolved(id, result.moduleVersionId)
            return
        }

        try {
            result.resolved(
                CodevGradleLinkageLoader.wrapComponentMetadata(
                    metadata,
                    metadataDelegate(id, dependency.artifacts.firstOrNull()),
                    resolvers,
                    objects,
                ),
            )
        } catch (error: Throwable) {
            result.failed(ModuleVersionResolveException(dependency.selector, error))
        }
    }

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier !is ExtractIncludesComponentIdentifier) return

        resolvers.get().componentResolver.resolve(identifier.original, componentOverrideMetadata, result)

        if (!result.hasResult() || result.failure != null) return

        try {
            result.resolved(
                CodevGradleLinkageLoader.wrapComponentMetadata(
                    result.state,
                    metadataDelegate(identifier, componentOverrideMetadata.artifact),
                    resolvers,
                    objects,
                ),
            )
        } catch (error: Throwable) {
            result.failed(ModuleVersionResolveException(identifier, error))
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = false

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is ExtractIncludesComponentIdentifier) {
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
        if (artifact is ExtractIncludesComponentArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

            if (result.isSuccessful) {
                val includeRules =
                    project.extensions.getByType(
                        MinecraftCodevExtension::class.java,
                    ).extensions.getByType(IncludesExtension::class.java).rules

                val base = result.result

                val handler =
                    zipFileSystem(base.file.toPath()).use {
                        val root = it.base.getPath("/")

                        includeRules.get().firstNotNullOfOrNull { rule ->
                            rule.load(root)
                        }
                    } ?: return

                val id = artifact.componentId

                getOrResolve(
                    component,
                    artifact as ModuleComponentArtifactMetadata,
                    calculatedValueContainerFactory,
                    artifact.id.asSerializable,
                    artifactCache,
                    cachePolicy,
                    timeProvider,
                    result,
                ) {
                    buildOperationExecutor.call(
                        object : CallableBuildOperation<Path> {
                            override fun description() =
                                BuildOperationDescriptor
                                    .displayName("Removing includes from ${result.result}")
                                    .progressDisplayName("Removing extracted includes")
                                    .metadata(BuildOperationCategory.TASK)

                            override fun call(context: BuildOperationContext) =
                                context.callWithStatus {
                                    val file = Files.createTempFile("includes-extracted", ".jar")

                                    base.file.toPath().copyTo(file, true)

                                    zipFileSystem(file).use {
                                        val root = it.base.getPath("/")

                                        for (jar in handler.list(root)) {
                                            it.base.getPath(jar.path).deleteExisting()
                                        }

                                        handler.remove(root)
                                    }

                                    val output =
                                        cacheManager.fileStoreDirectory
                                            .resolve(id.group)
                                            .resolve(id.module)
                                            .resolve(id.version)
                                            .resolve(checksumService.sha1(file.toFile()).toString())
                                            .resolve("${base.file.nameWithoutExtension}-without-includes.${base.file.extension}")

                                    output.parent.createDirectories()
                                    file.copyTo(output)

                                    output
                                }
                        },
                    ).toFile()
                }

                if (!result.hasResult()) {
                    result.notFound(artifact.id)
                }
            }
        } else if (artifact is PassthroughArtifactMetadata) {
            resolvers.get().artifactResolver.resolveArtifact(component, artifact.original, result)
        }
    }
}

data class ExtractIncludesComponentIdentifier(val original: ModuleComponentIdentifier) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "${original.displayName} (Includes Extracted)"

    override fun toString() = displayName
}
