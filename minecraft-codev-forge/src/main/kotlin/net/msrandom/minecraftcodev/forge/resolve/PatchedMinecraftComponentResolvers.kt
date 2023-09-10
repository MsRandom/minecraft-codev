package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftDependencyToComponentIdResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.dependency.FmlLoaderWrappedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadata
import net.msrandom.minecraftcodev.forge.mappings.injectForgeMappingService
import net.msrandom.minecraftcodev.forge.resolve.PatchedSetupState.Companion.getClientExtrasOutput
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.copy
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import javax.inject.Inject
import kotlin.io.path.Path

open class PatchedMinecraftComponentResolvers @Inject constructor(
    private val project: Project,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val objectFactory: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val resolvers: ComponentResolversChainProvider,

    repositoriesSupplier: RepositoriesSupplier,
    cacheProvider: CodevCacheProvider
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val minecraftCacheManager = cacheProvider.manager("minecraft")
    private val patchedCacheManager = cacheProvider.manager("patched")

    private val artifactCache by lazy {
        patchedCacheManager.getMetadataCache(Path("module-artifact"), MinecraftArtifactResolver.Companion::artifactIdSerializer) {
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

    private fun wrapMetadata(metadata: ComponentResolveMetadata, id: FmlLoaderWrappedComponentIdentifier) = metadata.copy(
        objectFactory,
        id,
        {
            val mapArtifact = { artifact: ComponentArtifactMetadata ->
                if (artifact is ModuleComponentArtifactMetadata && artifact.name.type == ArtifactTypeDefinition.JAR_TYPE) {
                    FmlLoaderWrappedMetadata(artifact, id)
                } else {
                    artifact
                }
            }

            copy(
                objectFactory,
                { it },
                attributes,
                { this },
                mapArtifact,
                { map(mapArtifact) }
            )
        },
        { this }
    )

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is PatchedMinecraftDependencyMetadata) {
            componentIdResolver.resolveVersion(dependency, acceptor, rejector, result) { _, version ->
                PatchedComponentIdentifier(version, dependency.relatedConfiguration ?: MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)
            }
        } else if (dependency is ModuleDependencyMetadata && result !is WrappedDependencyComponentIdResult) {
            if ((dependency.selector.group != FmlLoaderWrappedComponentIdentifier.MINECRAFT_FORGE_GROUP || dependency.selector.module != FmlLoaderWrappedComponentIdentifier.FML_LOADER_MODULE) &&
                (dependency.selector.group != FmlLoaderWrappedComponentIdentifier.NEO_FORGED_GROUP || dependency.selector.module != FmlLoaderWrappedComponentIdentifier.NEO_FORGED_LOADER_MODULE)
            ) {
                return
            }

            val idResult = WrappedDependencyComponentIdResult()
            resolvers.get().componentIdResolver.resolve(dependency, acceptor, rejector, idResult)

            if (idResult.hasResult() && idResult.failure == null) {
                val id = FmlLoaderWrappedComponentIdentifier(idResult.id as ModuleComponentIdentifier)

                if (idResult.metadata == null) {
                    result.resolved(id, idResult.moduleVersionId)
                } else {
                    wrapMetadata(idResult.metadata!!, id)
                }
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is PatchedComponentIdentifier) {
            val patches = mutableListOf<File>()
            project.visitConfigurationFiles(resolvers, project.configurations.getByName(identifier.patches), null, patches::add)

            val config = UserdevConfig.fromFile(patches.first())

            if (config != null) {
                for (repository in repositories) {
                    objectFactory.newInstance(MinecraftMetadataGenerator::class.java, minecraftCacheManager).resolveMetadata(
                        repository,
                        config.libraries,
                        listOf(
                            DefaultModuleComponentArtifactMetadata(
                                DefaultModuleComponentArtifactIdentifier(
                                    identifier,
                                    identifier.module,
                                    ArtifactTypeDefinition.ZIP_TYPE,
                                    ArtifactTypeDefinition.ZIP_TYPE,
                                    PatchedSetupState.CLIENT_EXTRA
                                )
                            )
                        ),
                        repository.resourceAccessor,
                        identifier,
                        componentOverrideMetadata,
                        result,
                        MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE
                    )

                    if (result.hasResult()) {
                        return
                    }
                }
            }
        } else if (identifier is FmlLoaderWrappedComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.delegate, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(wrapMetadata(result.metadata, identifier))
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
    } else {
        null
    }

    override fun resolveArtifactsWithType(component: ComponentResolveMetadata, artifactType: ArtifactType, result: BuildableArtifactSetResolveResult) {
    }

    override fun resolveArtifact(artifact: ComponentArtifactMetadata, moduleSources: ModuleSources, result: BuildableArtifactResolveResult) {
        if (artifact.componentId is PatchedComponentIdentifier) {
            val moduleComponentIdentifier = artifact.componentId as PatchedComponentIdentifier

            if (artifact.name.classifier == PatchedSetupState.CLIENT_EXTRA) {
                for (repository in repositories) {
                    val manifest = MinecraftMetadataGenerator.getVersionManifest(
                        moduleComponentIdentifier,
                        repository.url,
                        minecraftCacheManager,
                        cachePolicy,
                        repository.resourceAccessor,
                        checksumService,
                        timeProvider,
                        null
                    )

                    if (manifest != null) {
                        val clientJar = resolveMojangFile(
                            manifest,
                            minecraftCacheManager,
                            checksumService,
                            repository,
                            MinecraftComponentResolvers.CLIENT_DOWNLOAD
                        )

                        if (clientJar != null) {
                            val clientExtra = getClientExtrasOutput(
                                moduleComponentIdentifier,
                                manifest,
                                minecraftCacheManager,
                                cachePolicy,
                                clientJar,
                                project
                            )

                            result.resolved(clientExtra)

                            return
                        }
                    }
                }

                result.notFound(artifact.id)
            } else {
                val patches = mutableListOf<File>()
                project.visitConfigurationFiles(resolvers, project.configurations.getByName(moduleComponentIdentifier.patches), null, patches::add)

                getPatchedOutput(
                    moduleComponentIdentifier,
                    repositories,
                    minecraftCacheManager,
                    patchedCacheManager,
                    cachePolicy,
                    checksumService,
                    timeProvider,
                    patches.first(),
                    project,
                    objectFactory
                )?.let(result::resolved) ?: result.notFound(artifact.id)
            }
        } else if (artifact is FmlLoaderWrappedMetadata) {
            getOrResolve(
                artifact as ModuleComponentArtifactMetadata,
                artifact.id,
                artifactCache,
                cachePolicy,
                timeProvider,
                result,
            ) {
                resolvers.get().artifactResolver.resolveArtifact(artifact.delegate, moduleSources, result)

                if (result.hasResult() && result.failure == null) {
                    val fmlLoader = File.createTempFile("fmlloader-patch", ".jar")

                    result.result.copyTo(fmlLoader, true)
                    if (injectForgeMappingService(fmlLoader.toPath())) {
                        val output = patchedCacheManager.fileStoreDirectory
                            .resolve(artifact.componentId.group)
                            .resolve(artifact.componentId.module)
                            .resolve(artifact.componentId.version)
                            .resolve(checksumService.sha1(fmlLoader).toString())
                            .toFile()

                        fmlLoader.copyTo(output)

                        output
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    companion object {
        fun getPatchedOutput(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            repositories: List<MinecraftRepositoryImpl.Resolver>,
            cacheProvider: CodevCacheProvider,
            cachePolicy: CachePolicy,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            patches: File,
            project: Project,
            objectFactory: ObjectFactory
        ) = getPatchedOutput(
            moduleComponentIdentifier,
            repositories,
            cacheProvider.manager("minecraft"),
            cacheProvider.manager("patched"),
            cachePolicy,
            checksumService,
            timeProvider,
            patches,
            project,
            objectFactory
        )

        private fun getPatchedOutput(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            repositories: List<MinecraftRepositoryImpl.Resolver>,
            minecraftCacheManager: CodevCacheManager,
            patchedCacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            patches: File,
            project: Project,
            objectFactory: ObjectFactory
        ): File? {
            for (repository in repositories) {
                val manifest = MinecraftMetadataGenerator.getVersionManifest(
                    moduleComponentIdentifier,
                    repository.url,
                    minecraftCacheManager,
                    cachePolicy,
                    repository.resourceAccessor,
                    checksumService,
                    timeProvider,
                    null
                )

                if (manifest != null) {
                    val clientJar = resolveMojangFile(
                        manifest,
                        minecraftCacheManager,
                        checksumService,
                        repository,
                        MinecraftComponentResolvers.CLIENT_DOWNLOAD
                    )

                    val serverJar = resolveMojangFile(
                        manifest,
                        minecraftCacheManager,
                        checksumService,
                        repository,
                        MinecraftComponentResolvers.SERVER_DOWNLOAD
                    )

                    if (clientJar != null && serverJar != null) {
                        return PatchedSetupState.getForgePatchedOutput(
                            moduleComponentIdentifier,
                            manifest,
                            clientJar,
                            serverJar,
                            patches,
                            minecraftCacheManager,
                            patchedCacheManager,
                            cachePolicy,
                            project,
                            objectFactory
                        )
                    }
                }
            }

            return null
        }
    }
}

/**
 * Used as a marker that this resolver should be skipped
 */
open class WrappedDependencyComponentIdResult : DefaultBuildableComponentIdResolveResult()
