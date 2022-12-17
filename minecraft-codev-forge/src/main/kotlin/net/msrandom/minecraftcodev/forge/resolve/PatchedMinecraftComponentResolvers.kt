package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.unsafeResolveConfiguration
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.getSourceSetConfigurationName
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.core.resolve.MinecraftDependencyToComponentIdResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadata
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultResolvedModuleVersion
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.cache.scopes.GlobalScopedCache
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.hash.HashCode
import org.gradle.internal.model.CalculatedValueContainerFactory
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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.io.path.Path

open class PatchedMinecraftComponentResolvers @Inject constructor(
    private val project: Project,
    private val fileStoreAndIndexProvider: FileStoreAndIndexProvider,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val objectFactory: ObjectFactory,
    private val globalScopedCache: GlobalScopedCache,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,

    repositoriesSupplier: RepositoriesSupplier,
    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val minecraftCacheManager = cacheProvider.manager("minecraft")
    private val patchedCacheManager = cacheProvider.manager("patched")

    private val artifactCache by lazy {
        patchedCacheManager.getMetadataCache(Path("module-artifact"), { PatchedArtifactIdentifier.ArtifactSerializer }) {
            CachedArtifactSerializer(patchedCacheManager.fileStoreDirectory)
        }.asFile
    }

    private val repositories = repositoriesSupplier.get()
        .filterIsInstance<MinecraftRepositoryImpl>()
        .map(MinecraftRepositoryImpl::createResolver)

    private val componentIdResolver = objectFactory.newInstance(MinecraftDependencyToComponentIdResolver::class.java, repositories)

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    private fun getPatchState(moduleComponentIdentifier: ModuleComponentIdentifier, patches: File, patchesHash: HashCode, shouldRefresh: (File, Duration) -> Boolean): PatchedSetupState? {
        for (repository in repositories) {
            val manifest = MinecraftMetadataGenerator.getVersionManifest(
                moduleComponentIdentifier,
                repository.url,
                minecraftCacheManager,
                cachePolicy,
                repository.transport.resourceAccessor,
                fileStoreAndIndexProvider,
                null
            )

            if (manifest != null) {
                val clientJar = resolveMojangFile(
                    manifest,
                    minecraftCacheManager,
                    checksumService,
                    repository,
                    MinecraftComponentResolvers.CLIENT_DOWNLOAD,
                    shouldRefresh
                )

                val serverJar = resolveMojangFile(
                    manifest,
                    minecraftCacheManager,
                    checksumService,
                    repository,
                    MinecraftComponentResolvers.SERVER_DOWNLOAD,
                    shouldRefresh
                )

                if (clientJar != null && serverJar != null) {
                    return PatchedSetupState.getPatchedState(manifest, clientJar, serverJar, patches, patchesHash, patchedCacheManager, objectFactory)
                }
            }
        }

        return null
    }

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is PatchedMinecraftDependencyMetadata) {
            componentIdResolver.resolveVersion(dependency, acceptor, rejector, result) { module, version ->
                PatchedComponentIdentifier(
                    module,
                    version,
                    project.getSourceSetConfigurationName(dependency, MinecraftCodevForgePlugin.PATCHES_CONFIGURATION),
                    dependency.getModuleConfiguration()
                )
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is PatchedComponentIdentifier && identifier.module == "forge") {
            val patches = project.unsafeResolveConfiguration(project.configurations.getByName(identifier.patches))
            val config = UserdevConfig.fromFile(patches.singleFile)

            if (config != null) {
                for (repository in repositories) {
                    objectFactory.newInstance(MinecraftMetadataGenerator::class.java, minecraftCacheManager).resolveMetadata(
                        repository,
                        config.libraries,
                        repository.transport.resourceAccessor,
                        identifier,
                        componentOverrideMetadata,
                        result,
                        MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
                        patches
                    )

                    if (result.hasResult()) {
                        return
                    }
                }
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = true

    override fun resolveArtifacts(
        component: ComponentResolveMetadata,
        configuration: ConfigurationMetadata,
        artifactTypeRegistry: ArtifactTypeRegistry,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes
    ) = if (component.id is PatchedComponentIdentifier) {
        val moduleComponentIdentifier = component.id as PatchedComponentIdentifier
        val patches = project.unsafeResolveConfiguration(project.configurations.getByName(moduleComponentIdentifier.patches))
        val patchState = getPatchState(moduleComponentIdentifier, patches.singleFile, hash(patches)) { _, duration ->
            cachePolicy.moduleExpiry(
                moduleComponentIdentifier,
                DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version)),
                duration
            ).isMustCheck
        }

        if (patchState == null) {
            null
        } else {
            // Force it to resolve here to allow it to resolve needed configurations
            patchState.withAssets

            MetadataSourcedComponentArtifacts().getArtifactsFor(
                component,
                configuration,
                this,
                hashMapOf(),
                artifactTypeRegistry,
                exclusions,
                overriddenAttributes,
                calculatedValueContainerFactory
            )
        }
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
    }

    // Not the most efficient hashing, but works.
    private fun hash(patches: Configuration) = HashCode.fromBytes(patches.map(checksumService::sha1).flatMap { it.toByteArray().asList() }.toByteArray())

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact.componentId is PatchedComponentIdentifier) {
            val moduleComponentIdentifier = artifact.componentId as PatchedComponentIdentifier
            val patches = project.configurations.getByName(moduleComponentIdentifier.patches)

            val id = artifact.id as ModuleComponentArtifactIdentifier

            val patchesHash = hash(patches)

            val urlId = PatchedArtifactIdentifier(
                ModuleComponentFileArtifactIdentifier(
                    DefaultModuleComponentIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                    id.fileName
                ), patchesHash
            )

            val cached = artifactCache[urlId]

            if (cached == null || cachePolicy.artifactExpiry(
                    (artifact as ModuleComponentArtifactMetadata).toArtifactIdentifier(),
                    if (cached.isMissing) null else cached.cachedFile,
                    Duration.ofMillis(timeProvider.currentTime - cached.cachedAt),
                    false,
                    artifact.hash() == cached.descriptorHash
                ).isMustCheck || cached.cachedFile?.exists() != true
            ) {
                val sources = artifact.name.classifier == "sources"

                val shouldRefresh = { file: File, duration: Duration ->
                    cachePolicy.artifactExpiry(
                        (artifact as ModuleComponentArtifactMetadata).toArtifactIdentifier(),
                        file,
                        duration,
                        false,
                        true
                    ).isMustCheck
                }

                getPatchState(moduleComponentIdentifier, patches.singleFile, patchesHash, shouldRefresh)?.withAssets?.let {
                    val file = it.toFile()
                    result.resolved(file)

                    artifactCache[urlId] = DefaultCachedArtifact(file, Instant.now().toEpochMilli(), artifact.hash())
                }
            } else if (!cached.isMissing) {
                result.resolved(cached.cachedFile)
            }
        }
    }
}
