package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.caches.CachedArtifactSerializer
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.caches.CodevCacheProvider
import net.msrandom.minecraftcodev.core.repository.DefaultCacheAwareExternalResourceAccessor
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.*
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getOrResolve
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.collectLibraries
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.defaultAttributes
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.extractionState
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.getVersionList
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.getVersionManifest
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.libraryAttributes
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator.Companion.mapLibrary
import net.msrandom.minecraftcodev.core.utils.visitConfigurationFiles
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.Userdev
import net.msrandom.minecraftcodev.forge.dependency.FmlLoaderWrappedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedComponentIdentifier
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependencyMetadata
import net.msrandom.minecraftcodev.forge.mappings.injectForgeMappingService
import net.msrandom.minecraftcodev.forge.resolve.PatchedSetupState.Companion.getClientExtrasOutput
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader.wrapComponentMetadata
import net.msrandom.minecraftcodev.gradle.api.*
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultResolvableArtifact
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector
import org.gradle.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultResolvedModuleVersion
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.ComponentResolversChain
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ArtifactSetFactory
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedVariant
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.specs.ExcludeSpec
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.component.ArtifactType
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.Describables
import org.gradle.internal.component.external.model.*
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.model.CalculatedValueContainerFactory
import org.gradle.internal.model.ValueCalculator
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver
import org.gradle.internal.resolve.resolver.OriginArtifactSelector
import org.gradle.internal.resolve.result.*
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.io.File
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.io.path.Path

open class PatchedMinecraftComponentResolvers
@Inject
constructor(
    private val project: Project,
    private val calculatedValueContainerFactory: CalculatedValueContainerFactory,
    private val attributesSchema: AttributesSchemaInternal,
    private val objectFactory: ObjectFactory,
    private val cachePolicy: CachePolicy,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val resolvers: ComponentResolversChainProvider,
    repositoriesSupplier: RepositoriesSupplier,
    cacheProvider: CodevCacheProvider,
) : ComponentResolvers, DependencyToComponentIdResolver, ComponentMetaDataResolver, OriginArtifactSelector, ArtifactResolver {
    private val minecraftCacheManager = cacheProvider.manager("minecraft")
    private val patchedCacheManager = cacheProvider.manager("patched")

    private val artifactCache by lazy {
        patchedCacheManager.getMetadataCache(Path("module-artifact"), MinecraftArtifactResolver.Companion::artifactIdSerializer) {
            CachedArtifactSerializer(patchedCacheManager.fileStoreDirectory)
        }.asFile
    }

    private val repositories =
        repositoriesSupplier.get()
            .filterIsInstance<MinecraftRepositoryImpl>()
            .map(MinecraftRepositoryImpl::createResolver)

    private val componentIdResolver = objectFactory.newInstance(MinecraftDependencyToComponentIdResolver::class.java, repositories)

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
        if (dependency is PatchedMinecraftDependencyMetadata) {
            componentIdResolver.resolveVersion(dependency, acceptor, rejector, result) { _, version ->
                PatchedComponentIdentifier(
                    version,
                    dependency.relatedConfiguration.takeUnless(String::isEmpty) ?: MinecraftCodevForgePlugin.PATCHES_CONFIGURATION,
                )
            }
            return
        }
        if (dependency !is ModuleDependencyMetadata || result is WrappedDependencyComponentIdResult) return

        if ((dependency.selector.group != FmlLoaderWrappedComponentIdentifier.MINECRAFT_FORGE_GROUP || dependency.selector.module != FmlLoaderWrappedComponentIdentifier.FML_LOADER_MODULE) &&
            (dependency.selector.group != FmlLoaderWrappedComponentIdentifier.NEO_FORGED_GROUP || dependency.selector.module != FmlLoaderWrappedComponentIdentifier.NEO_FORGED_LOADER_MODULE)
        ) {
            return
        }

        val idResult = WrappedDependencyComponentIdResult()
        resolvers.get().componentIdResolver.resolve(dependency, acceptor, rejector, idResult)

        if (!idResult.hasResult() || idResult.failure != null) return

        val id = FmlLoaderWrappedComponentIdentifier(idResult.id as ModuleComponentIdentifier)

        if (idResult.state == null) {
            result.resolved(id, idResult.moduleVersionId)
        } else {
            result.resolved(
                wrapComponentMetadata(idResult.state!!, FmlLoaderComponentMetadataDelegate(id), resolvers, objectFactory),
            )
        }
    }

    private fun generateMetadata(
        repository: MinecraftRepositoryImpl.Resolver,
        extraLibraries: List<ModuleLibraryIdentifier>,
        extraArtifacts: List<ComponentArtifactMetadata>,
        resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
        moduleComponentIdentifier: PatchedComponentIdentifier,
        requestMetaData: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ): ComponentMetadataHolder? {
        val versionList =
            getVersionList(
                moduleComponentIdentifier,
                DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier)),
                repository.url,
                minecraftCacheManager,
                cachePolicy,
                resourceAccessor,
                checksumService,
                timeProvider,
                null,
            )

        val manifest =
            getVersionManifest(
                moduleComponentIdentifier,
                resourceAccessor,
                cachePolicy,
                minecraftCacheManager,
                checksumService,
                timeProvider,
                result::attempted,
                versionList,
            )

        if (versionList == null || manifest == null) {
            return null
        }

        fun artifact(
            extension: String,
            classifier: String? = null,
        ) = if (classifier == null) {
            object : MainArtifact, DefaultModuleComponentArtifactMetadata(
                moduleComponentIdentifier,
                DefaultIvyArtifactName(
                    moduleComponentIdentifier.module,
                    extension,
                    extension,
                    null,
                ),
            ) {}
        } else {
            DefaultModuleComponentArtifactMetadata(
                moduleComponentIdentifier,
                DefaultIvyArtifactName(
                    moduleComponentIdentifier.module,
                    extension,
                    extension,
                    classifier,
                ),
            )
        }

        val attributesFactory = project.serviceOf<ImmutableAttributesFactory>()
        val instantiator = project.serviceOf<NamedObjectInstantiator>()

        fun ImmutableAttributes.addNamed(
            attribute: Attribute<*>,
            value: String,
        ) = addNamed(
            attributesFactory,
            instantiator,
            attribute,
            value,
        )

        val defaultAttributes =
            ImmutableAttributes.EMPTY.addNamed(
                MappingsNamespace.attribute,
                MinecraftCodevForgePlugin.SRG_MAPPINGS_NAMESPACE,
            )
        val extractionResult = extractionState(repository, manifest, minecraftCacheManager, project.serviceOf(), checksumService).value
        val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())
        val artifact = artifact(ArtifactTypeDefinition.JAR_TYPE)
        val artifacts = listOf(artifact) + extraArtifacts

        val variants =
            libraries.client.asMap().map { (system, platformLibraries) ->
                val osAttributes: ImmutableAttributes
                val name: String
                val dependencies: List<DependencyMetadata>

                if (system == null) {
                    osAttributes = ImmutableAttributes.EMPTY
                    name = Dependency.DEFAULT_CONFIGURATION
                    dependencies = (libraries.common + extraLibraries + platformLibraries).map(::mapLibrary)
                } else {
                    val systemVersion = system.version

                    if (systemVersion == null) {
                        osAttributes =
                            ImmutableAttributes.EMPTY
                                .addNamed(
                                    OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                                    DefaultOperatingSystem(system.name).toFamilyName(),
                                )

                        name = system.name.toString()
                    } else {
                        val version = systemVersion.replace(Regex("[$^\\\\]"), "").replace('.', '-')

                        osAttributes =
                            ImmutableAttributes.EMPTY
                                .addNamed(
                                    OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                                    DefaultOperatingSystem(system.name).toFamilyName(),
                                )
                                .addNamed(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE, systemVersion)

                        name = "${system.name}-$version"
                    }

                    dependencies = (libraries.common + extraLibraries + libraries.client[null] + platformLibraries).map(::mapLibrary)
                }

                VariantMetadataHolder(
                    name,
                    moduleComponentIdentifier,
                    Describables.of(moduleComponentIdentifier, "variant", name),
                    dependencies,
                    artifacts,
                    attributesFactory.concat(
                        defaultAttributes(
                            attributesFactory,
                            instantiator,
                            manifest,
                            defaultAttributes,
                        ).libraryAttributes(attributesFactory, instantiator),
                        osAttributes,
                    ),
                    ImmutableCapabilities.EMPTY,
                    setOf(name),
                )
            }

        return ComponentMetadataHolder(
            moduleComponentIdentifier,
            attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
            DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
            variants,
            requestMetaData.isChanging,
            manifest.type,
            versionList.latest.keys.reversed(),
        )
    }

    override fun resolve(
        identifier: ComponentIdentifier,
        componentOverrideMetadata: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
    ) {
        if (identifier is PatchedComponentIdentifier) {
            val patches = mutableListOf<Supplier<File>>()
            project.visitConfigurationFiles(resolvers, project.configurations.getByName(identifier.patches), null, patches::add)

            val config = Userdev.fromFiles(patches)?.config ?: return

            for (repository in repositories) {
                val metadata =
                    generateMetadata(
                        repository,
                        config.libraries,
                        listOf(
                            DefaultModuleComponentArtifactMetadata(
                                DefaultModuleComponentArtifactIdentifier(
                                    identifier,
                                    identifier.module,
                                    ArtifactTypeDefinition.ZIP_TYPE,
                                    ArtifactTypeDefinition.ZIP_TYPE,
                                    PatchedSetupState.CLIENT_EXTRA,
                                ),
                            ),
                        ),
                        repository.resourceAccessor,
                        identifier,
                        componentOverrideMetadata,
                        result,
                    ) ?: continue

                result.resolved(wrapComponentMetadata(metadata, objectFactory))

                return
            }
        } else if (identifier is FmlLoaderWrappedComponentIdentifier) {
            resolvers.get().componentResolver.resolve(identifier.delegate, componentOverrideMetadata, result)

            if (result.hasResult() && result.failure == null) {
                result.resolved(
                    wrapComponentMetadata(result.state, FmlLoaderComponentMetadataDelegate(identifier), resolvers, objectFactory),
                )
            }
        }
    }

    override fun isFetchingMetadataCheap(identifier: ComponentIdentifier) = true

    override fun resolveArtifacts(
        component: ComponentArtifactResolveMetadata,
        allVariants: ComponentArtifactResolveVariantState,
        legacyVariants: MutableSet<ResolvedVariant>,
        exclusions: ExcludeSpec,
        overriddenAttributes: ImmutableAttributes,
    ) = if (component.id is PatchedComponentIdentifier) {
        ArtifactSetFactory.createFromVariantMetadata(
            component.id,
            allVariants,
            legacyVariants,
            attributesSchema,
            overriddenAttributes,
        )
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
        if (artifact.componentId is PatchedComponentIdentifier) {
            val moduleComponentIdentifier = artifact.componentId as PatchedComponentIdentifier

            if (artifact.name.classifier == PatchedSetupState.CLIENT_EXTRA) {
                for (repository in repositories) {
                    val manifest =
                        getVersionManifest(
                            moduleComponentIdentifier,
                            repository.url,
                            minecraftCacheManager,
                            cachePolicy,
                            repository.resourceAccessor,
                            checksumService,
                            timeProvider,
                            null,
                        )

                    if (manifest != null) {
                        val clientJar =
                            resolveMojangFile(
                                manifest,
                                minecraftCacheManager,
                                checksumService,
                                repository,
                                MinecraftComponentResolvers.CLIENT_DOWNLOAD,
                            )

                        if (clientJar != null) {
                            result.resolved(
                                DefaultResolvableArtifact(
                                    component.moduleVersionId,
                                    artifact.name,
                                    artifact.id,
                                    {},
                                    calculatedValueContainerFactory.create(
                                        Describables.of(artifact.id),
                                        ValueCalculator {
                                            getClientExtrasOutput(
                                                moduleComponentIdentifier,
                                                manifest,
                                                minecraftCacheManager,
                                                cachePolicy,
                                                clientJar,
                                                resolvers,
                                                project,
                                            )
                                        },
                                    ),
                                    calculatedValueContainerFactory,
                                ),
                            )

                            return
                        }
                    }
                }

                result.notFound(artifact.id)
            } else {
                val patches = mutableListOf<Supplier<File>>()

                project.visitConfigurationFiles(
                    resolvers,
                    project.configurations.getByName(moduleComponentIdentifier.patches),
                    null,
                    patches::add,
                )

                val patchedOutput =
                    getPatchedOutput(
                        moduleComponentIdentifier,
                        repositories,
                        minecraftCacheManager,
                        patchedCacheManager,
                        cachePolicy,
                        checksumService,
                        timeProvider,
                        Userdev.fromFiles(patches)!!,
                        resolvers.get(),
                        project,
                        objectFactory,
                    ) ?: return result.notFound(artifact.id)

                result.resolved(
                    DefaultResolvableArtifact(
                        component.moduleVersionId,
                        artifact.name,
                        artifact.id,
                        {},
                        calculatedValueContainerFactory.create(Describables.of(artifact.id), ValueCalculator { patchedOutput }),
                        calculatedValueContainerFactory,
                    ),
                )
            }
        } else if (artifact is FmlLoaderWrappedMetadata) {
            getOrResolve(
                component,
                artifact as ModuleComponentArtifactMetadata,
                calculatedValueContainerFactory,
                artifact.id,
                artifactCache,
                cachePolicy,
                timeProvider,
                result,
            ) {
                resolvers.get().artifactResolver.resolveArtifact(component, artifact.delegate, result)

                if (result.hasResult() && result.failure == null) {
                    val fmlLoader = File.createTempFile("fmlloader-patch", ".jar")

                    result.result.file.copyTo(fmlLoader, true)
                    if (injectForgeMappingService(fmlLoader.toPath())) {
                        val output =
                            patchedCacheManager.fileStoreDirectory
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
            patches: Userdev,
            resolvers: ComponentResolversChain,
            project: Project,
            objectFactory: ObjectFactory,
        ) = getPatchedOutput(
            moduleComponentIdentifier,
            repositories,
            cacheProvider.manager("minecraft"),
            cacheProvider.manager("patched"),
            cachePolicy,
            checksumService,
            timeProvider,
            patches,
            resolvers,
            project,
            objectFactory,
        )

        private fun getPatchedOutput(
            moduleComponentIdentifier: PatchedComponentIdentifier,
            repositories: List<MinecraftRepositoryImpl.Resolver>,
            minecraftCacheManager: CodevCacheManager,
            patchedCacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            patches: Userdev,
            resolvers: ComponentResolversChain,
            project: Project,
            objectFactory: ObjectFactory,
        ): File? {
            for (repository in repositories) {
                val manifest =
                    getVersionManifest(
                        moduleComponentIdentifier,
                        repository.url,
                        minecraftCacheManager,
                        cachePolicy,
                        repository.resourceAccessor,
                        checksumService,
                        timeProvider,
                        null,
                    )

                if (manifest != null) {
                    val clientJar =
                        resolveMojangFile(
                            manifest,
                            minecraftCacheManager,
                            checksumService,
                            repository,
                            MinecraftComponentResolvers.CLIENT_DOWNLOAD,
                        )

                    val serverJar =
                        resolveMojangFile(
                            manifest,
                            minecraftCacheManager,
                            checksumService,
                            repository,
                            MinecraftComponentResolvers.SERVER_DOWNLOAD,
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
                            resolvers,
                            project,
                            objectFactory,
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
