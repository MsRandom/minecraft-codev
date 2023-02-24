package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.ComponentResolversChainProvider
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftDependencyToComponentIdResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator
import net.msrandom.minecraftcodev.core.utils.getSourceSetConfigurationName
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.UserdevConfig
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadata
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.MetadataSourcedComponentArtifacts
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
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
import javax.inject.Inject

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

    private val repositories = repositoriesSupplier.get()
        .filterIsInstance<MinecraftRepositoryImpl>()
        .map(MinecraftRepositoryImpl::createResolver)

    private val componentIdResolver = objectFactory.newInstance(MinecraftDependencyToComponentIdResolver::class.java, repositories)

    override fun getComponentIdResolver() = this
    override fun getComponentResolver() = this
    override fun getArtifactSelector() = this
    override fun getArtifactResolver() = this

    override fun resolve(dependency: DependencyMetadata, acceptor: VersionSelector?, rejector: VersionSelector?, result: BuildableComponentIdResolveResult) {
        if (dependency is PatchedMinecraftDependencyMetadata) {
            componentIdResolver.resolveVersion(dependency, acceptor, rejector, result) { _, version ->
                PatchedComponentIdentifier(
                    version,
                    project.getSourceSetConfigurationName(dependency, MinecraftCodevForgePlugin.PATCHES_CONFIGURATION),
                    dependency.getModuleConfiguration()
                )
            }
        }
    }

    override fun resolve(identifier: ComponentIdentifier, componentOverrideMetadata: ComponentOverrideMetadata, result: BuildableComponentResolveResult) {
        if (identifier is PatchedComponentIdentifier) {
            val patches = mutableListOf<File>()
            project.visitConfigurationFiles(resolvers, project.configurations.getByName(identifier.patches), emptyList(), patches::add)

            val config = UserdevConfig.fromFile(patches.first())

            if (config != null) {
                for (repository in repositories) {
                    objectFactory.newInstance(MinecraftMetadataGenerator::class.java, minecraftCacheManager).resolveMetadata(
                        repository,
                        config.libraries,
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
            val patches = mutableListOf<File>()
            project.visitConfigurationFiles(resolvers, project.configurations.getByName(moduleComponentIdentifier.patches), emptyList(), patches::add)

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
            )?.let(result::resolved)
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
                        return PatchedSetupState.getPatchedOutput(moduleComponentIdentifier, manifest, clientJar, serverJar, patches, patchedCacheManager, cachePolicy, project, objectFactory)
                    }
                }
            }

            return null
        }
    }
}
