package net.msrandom.minecraftcodev.accesswidener.resolve

import net.msrandom.minecraftcodev.accesswidener.AccessWidenedDependencyMetadata
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.getSourceSetConfigurationName
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.CachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.internal.serialize.MapSerializer
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

open class AccessWidenedComponentResolvers @Inject constructor(
    private val resolvers: ComponentResolversChain,
    private val project: Project,
    private val objects: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,

    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val cacheManager = cacheProvider.manager("access-widened")

    private val artifactCache by lazy {
        cacheManager.getMetadataCache(Path("module-artifact"), emptyMap<AccessWidenedArtifactIdentifier, CachedArtifact>()) {
            MapSerializer(AccessWidenedArtifactIdentifier.ArtifactSerializer, CachedArtifactSerializer(cacheManager.fileStoreDirectory))
        }.asFile
    }

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is AccessWidenedDependencyMetadata) {
            resolvers.componentIdResolver.resolve(dependency.delegate, acceptor, rejector, result)

            if (result.hasResult()) {
                if (result.failure == null) {
                    val metadata = result.metadata
                    val accessWidenersConfiguration = project.getSourceSetConfigurationName(dependency, MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION)

                    if (result.id is ModuleComponentIdentifier) {
                        if (metadata == null) {
                            result.resolved(
                                AccessWidenedComponentIdentifier(
                                    result.id as ModuleComponentIdentifier,
                                    accessWidenersConfiguration,
                                    dependency.getModuleConfiguration()
                                ),
                                result.moduleVersionId
                            )
                        } else {
                            // FIXME
                            result.resolved(object : ComponentResolveMetadata by metadata {
                                override fun getId() = AccessWidenedComponentIdentifier(
                                    result.id as ModuleComponentIdentifier,
                                    accessWidenersConfiguration,
                                    dependency.getModuleConfiguration()
                                )
                            })
                        }
                    }
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is AccessWidenedComponentIdentifier) {
            val newResult = DefaultBuildableComponentResolveResult()
            resolvers.componentResolver.resolve(identifier.original, componentOverrideMetadata, newResult)

            if (newResult.hasResult() && newResult.failure == null) {
                val existingMetadata = newResult.metadata

                val metadata = CodevGradleLinkageLoader.wrapComponentMetadata(
                    existingMetadata,
                    identifier,
                    { it },
                    objects
                )

                result.resolved(metadata)
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = identifier is AccessWidenedComponentIdentifier && resolvers.componentResolver.isFetchingMetadataCheap(identifier)

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is AccessWidenedComponentIdentifier) {
        MetadataSourcedComponentArtifacts().getArtifactsFor(
            component, configuration, artifactResolver, hashMapOf(), artifactTypeRegistry, exclusions, overriddenAttributes, calculatedValueContainerFactory
        )
        // resolvers.artifactSelector.resolveArtifacts(CodevGradleLinkageLoader.getDelegate(component), configuration, exclusions, overriddenAttributes)
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
        if (component.id is AccessWidenedComponentIdentifier) {
            resolvers.artifactResolver.resolveArtifactsWithType(component, artifactType, result)
        }
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        val id = artifact.componentId
        if (id is AccessWidenedComponentIdentifier) {
            val newResult = DefaultBuildableArtifactResolveResult()
            resolvers.artifactResolver.resolveArtifact(artifact, moduleSources, newResult)

            if (newResult.isSuccessful) {
                val cachedValues = artifactCache.value

                val urlId = AccessWidenedArtifactIdentifier(
                    ModuleComponentFileArtifactIdentifier(DefaultModuleComponentIdentifier.newId(id.moduleIdentifier, id.version), newResult.result.name),
                    TODO(),
                    checksumService.sha1(newResult.result)
                )

                val cached = cachedValues[urlId]

                if (cached == null || cachePolicy.artifactExpiry(
                        (artifact as ModuleComponentArtifactMetadata).toArtifactIdentifier(),
                        if (cached.isMissing) null else cached.cachedFile,
                        Duration.ofMillis(timeProvider.currentTime - cached.cachedAt),
                        false,
                        artifact.hash() == cached.descriptorHash
                    ).isMustCheck
                ) {
                    // TODO actually access widen lol
                    val file = newResult.result.toPath()

                    val output = cacheManager.fileStoreDirectory
                        .resolve(TODO().toString())
                        .resolve(id.group)
                        .resolve(id.module)
                        .resolve(id.version)
                        .resolve(checksumService.sha1(file.toFile()).toString())
                        .resolve("${newResult.result.nameWithoutExtension}-access-widened.${newResult.result.extension}")

                    output.parent.createDirectories()
                    file.copyTo(output)

                    val outputFile = output.toFile()

                    result.resolved(outputFile)

                    artifactCache.update(
                        cachedValues + (urlId to DefaultCachedArtifact(
                            outputFile,
                            Instant.now().toEpochMilli(),
                            artifact.hash()
                        ))
                    )
                } else if (!cached.isMissing) {
                    result.resolved(cached.cachedFile)
                }
            }
        }
    }
}

class AccessWidenedComponentIdentifier(
    val original: ModuleComponentIdentifier,
    val accessWidenersConfiguration: String,
    val moduleConfiguration: String?
) : ModuleComponentIdentifier by original {
    override fun getDisplayName() = "Access Widened ${original.displayName}"
}