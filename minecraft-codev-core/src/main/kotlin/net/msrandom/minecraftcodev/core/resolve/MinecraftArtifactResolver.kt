package net.msrandom.minecraftcodev.core.resolve

import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.asMinecraftDownload
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.hash
import net.msrandom.minecraftcodev.core.utils.asSerializable
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.CachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.api.internal.artifacts.metadata.ComponentArtifactIdentifierSerializer
import org.gradle.api.internal.artifacts.metadata.ModuleComponentFileArtifactIdentifierSerializer
import org.gradle.api.internal.component.ArtifactType
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactIdentifier
import org.gradle.internal.component.external.model.ModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.component.local.model.LocalComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import org.gradle.internal.resolve.result.BuildableArtifactSetResolveResult
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.local.LazyLocallyAvailableResourceCandidates
import org.gradle.internal.serialize.DefaultSerializerRegistry
import org.gradle.internal.serialize.Serializer
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import java.nio.file.Path
import java.time.Duration
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.exists

open class MinecraftArtifactResolver @Inject constructor(
    private val repositories: List<MinecraftRepositoryImpl.Resolver>,
    private val cachePolicy: CachePolicy,
    private val timeProvider: BuildCommencedTimeProvider,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val checksumService: ChecksumService,

    cacheProvider: CodevCacheProvider
) : ArtifactResolver {
    private val cacheManager = cacheProvider.manager("minecraft")

    private val artifactCache by lazy {
        // TODO make this more space efficient by removing the group
        cacheManager.getMetadataCache(Path("module-artifact"), ::artifactIdSerializer) {
            CachedArtifactSerializer(cacheManager.fileStoreDirectory)
        }.asFile
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        val componentIdentifier = artifact.componentId
        if (componentIdentifier::class == MinecraftComponentIdentifier::class) {
            componentIdentifier as MinecraftComponentIdentifier
            if (artifact is LocalComponentArtifactMetadata) {
                result.resolved(artifact.file)
            } else {
                getOrResolve(artifact as ModuleComponentArtifactMetadata, artifact.id.asSerializable, artifactCache, cachePolicy, timeProvider, result) {
                    for (repository in repositories) {
                        when (componentIdentifier.module) {
                            MinecraftComponentResolvers.COMMON_MODULE -> {
                                val manifest = getManifest(componentIdentifier, repository)
                                if (manifest != null) {
                                    val serverJar = resolveMojangFile(manifest, cacheManager, checksumService, repository, MinecraftComponentResolvers.SERVER_DOWNLOAD)

                                    if (serverJar != null) {
                                        val splitCommonJar by getCommonJar(cacheManager, buildOperationExecutor, checksumService, ::getArtifactPath, manifest, serverJar) {
                                            resolveMojangFile(manifest, cacheManager, checksumService, repository, MinecraftComponentResolvers.CLIENT_DOWNLOAD)!!
                                        }

                                        result.resolved(splitCommonJar.toFile())
                                        return@getOrResolve splitCommonJar.toFile()
                                    }
                                }
                            }

                            MinecraftComponentResolvers.CLIENT_MODULE -> {
                                val manifest = getManifest(componentIdentifier, repository)
                                if (manifest != null) {
                                    val clientJar = resolveMojangFile(manifest, cacheManager, checksumService, repository, MinecraftComponentResolvers.CLIENT_DOWNLOAD)
                                    val serverJar = resolveMojangFile(manifest, cacheManager, checksumService, repository, MinecraftComponentResolvers.SERVER_DOWNLOAD)

                                    if (clientJar != null) {
                                        // This being null means that we have no server Jar, which means that we could just use the actual client Jar provided.
                                        val client = if (serverJar == null) {
                                            clientJar
                                        } else {
                                            getClientJar(cacheManager, buildOperationExecutor, checksumService, ::getArtifactPath, manifest, clientJar, serverJar).toFile()
                                        }

                                        return@getOrResolve client
                                    }
                                }
                            }

                            else -> {
                                val manifest = getManifest(componentIdentifier, repository)

                                if (manifest != null) {
                                    val fixedName = componentIdentifier.module.asMinecraftDownload()

                                    if (fixedName != MinecraftComponentResolvers.SERVER_DOWNLOAD) {
                                        val download = manifest.downloads[fixedName]

                                        if (download != null) {
                                            val location = ExternalResourceName(download.url)
                                            result.attempted(location)
                                            val path = getArtifactPath(artifact.id as ModuleComponentArtifactIdentifier, download.sha1)
                                            val resource = repository.resourceAccessor.getResource(
                                                location,
                                                download.sha1,
                                                path,
                                                getAdditionalCandidates(path, checksumService)
                                            )

                                            return@getOrResolve resource?.file
                                        }
                                    }
                                }
                            }
                        }
                    }
                    null
                }
            }

            if (!result.hasResult()) {
                result.notFound(artifact.id)
            }
        }
    }

    private fun getManifest(
        moduleComponentIdentifier: MinecraftComponentIdentifier,
        repository: MinecraftRepositoryImpl.Resolver
    ) = MinecraftMetadataGenerator.getVersionManifest(
        moduleComponentIdentifier,
        repository.url,
        cacheManager,
        cachePolicy,
        repository.resourceAccessor,
        checksumService,
        timeProvider,
        null
    )

    private fun getArtifactPath(artifact: ModuleComponentArtifactIdentifier, sha1: String) = cacheManager.fileStoreDirectory
        .resolve(artifact.componentIdentifier.module)
        .resolve(artifact.componentIdentifier.version)
        .resolve(sha1)
        .resolve(artifact.fileName)

    companion object {
        val artifactIdSerializer: Serializer<ComponentArtifactIdentifier> = DefaultSerializerRegistry().run {
            register(DefaultModuleComponentArtifactIdentifier::class.java, ComponentArtifactIdentifierSerializer())
            register(ModuleComponentFileArtifactIdentifier::class.java, ModuleComponentFileArtifactIdentifierSerializer())

            build(ComponentArtifactIdentifier::class.java)
        }

        fun resolveMojangFile(
            manifest: MinecraftVersionMetadata,
            cacheManager: CodevCacheManager,
            checksumService: ChecksumService,
            repository: MinecraftRepositoryImpl.Resolver,
            download: String
        ): File? {
            return manifest.downloads[download]?.let {
                val path = cacheManager.rootPath.resolve("download-cache").resolve(it.sha1).resolve("$download.jar")
                repository.resourceAccessor.getResource(ExternalResourceName(it.url), it.sha1, path, getAdditionalCandidates(path, checksumService))?.file
            }
        }

        fun <T> getOrResolve(
            artifact: ModuleComponentArtifactMetadata,
            id: T,
            artifactCache: CodevCacheManager.CachedPath<T, CachedArtifact>.CachedFile,
            cachePolicy: CachePolicy,
            timeProvider: BuildCommencedTimeProvider,
            result: BuildableArtifactResolveResult,
            generate: () -> File?
        ) {
            val cached = artifactCache[id]

            if (cached == null || cachePolicy.artifactExpiry(
                    artifact.toArtifactIdentifier(),
                    if (cached.isMissing) null else cached.cachedFile,
                    Duration.ofMillis(timeProvider.currentTime - cached.cachedAt),
                    false,
                    artifact.hash() == cached.descriptorHash
                ).isMustCheck || cached.cachedFile?.exists() != true
            ) {
                val file = generate()

                if (file != null) {
                    result.resolved(file)
                    artifactCache[id] = DefaultCachedArtifact(file, timeProvider.currentTime, artifact.hash())
                }
            } else {
                if (!cached.isMissing) {
                    result.resolved(cached.cachedFile)
                }
            }
        }

        fun getAdditionalCandidates(path: Path, checksumService: ChecksumService) =
            LazyLocallyAvailableResourceCandidates({ if (path.exists()) listOf(path.toFile()) else emptyList() }, checksumService)
    }
}
