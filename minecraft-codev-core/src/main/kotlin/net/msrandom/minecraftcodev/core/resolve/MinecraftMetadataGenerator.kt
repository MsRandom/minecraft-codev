package net.msrandom.minecraftcodev.core.resolve

import com.google.common.collect.HashMultimap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import net.msrandom.minecraftcodev.core.LibraryData
import net.msrandom.minecraftcodev.core.MappingsNamespace
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyMetadataWrapper
import net.msrandom.minecraftcodev.core.repository.DefaultCacheAwareExternalResourceAccessor
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.getAdditionalCandidates
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.asMinecraftDownload
import net.msrandom.minecraftcodev.core.utils.named
import net.msrandom.minecraftcodev.gradle.api.ComponentMetadataHolder
import net.msrandom.minecraftcodev.gradle.api.VariantMetadataHolder
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedModuleVersion
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.*
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultResolvedModuleVersion
import org.gradle.api.internal.artifacts.publish.ImmutablePublishArtifact
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.initialization.layout.GlobalCacheDir
import org.gradle.internal.Describables
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.GradleDependencyMetadata
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.internal.component.local.model.PublishArtifactLocalArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.resolve.result.BuildableModuleVersionListingResolveResult
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.gradle.util.internal.BuildCommencedTimeProvider
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.io.path.*

open class MinecraftMetadataGenerator
@Inject
constructor(
    private val cacheManager: CodevCacheManager,
    private val globalCacheDir: GlobalCacheDir,
    private val attributesFactory: ImmutableAttributesFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val instantiator: NamedObjectInstantiator,
    private val objectFactory: ObjectFactory,
    private val checksumService: ChecksumService,
    private val timeProvider: BuildCommencedTimeProvider,
    private val cachePolicy: CachePolicy,
) {
    private fun extractionState(
        repository: MinecraftRepositoryImpl.Resolver,
        manifest: MinecraftVersionMetadata,
    ) = extractionState(repository, manifest, cacheManager, buildOperationExecutor, checksumService)

    fun resolveMetadata(
        repository: MinecraftRepositoryImpl.Resolver,
        resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
        identifier: MinecraftComponentIdentifier,
        isChanging: Boolean,
        requestMetaData: ComponentOverrideMetadata,
        attempt: (location: ExternalResourceName) -> Unit,
    ): ComponentMetadataHolder? {
        val versionList =
            getVersionList(
                identifier,
                DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, identifier.version)),
                repository.url,
                cacheManager,
                cachePolicy,
                resourceAccessor,
                checksumService,
                timeProvider,
                null,
            )

        val manifest =
            getVersionManifest(
                identifier,
                resourceAccessor,
                cachePolicy,
                cacheManager,
                checksumService,
                timeProvider,
                attempt,
                versionList,
            )

        if (versionList != null && manifest != null) {
            fun artifact(
                extension: String,
                classifier: String? = null,
            ) = if (classifier == null) {
                object : MainArtifact, DefaultModuleComponentArtifactMetadata(
                    identifier,
                    DefaultIvyArtifactName(
                        identifier.module,
                        extension,
                        extension,
                        null,
                    ),
                ) {}
            } else {
                DefaultModuleComponentArtifactMetadata(
                    identifier,
                    DefaultIvyArtifactName(
                        identifier.module,
                        extension,
                        extension,
                        classifier,
                    ),
                )
            }

            val artifact = artifact(ArtifactTypeDefinition.JAR_TYPE)

            val defaultAttributes = ImmutableAttributes.EMPTY.addNamed(MappingsNamespace.attribute, MappingsNamespace.OBF)
            when (identifier.module) {
                MinecraftComponentResolvers.COMMON_MODULE -> {
                    val extractionState = extractionState(repository, manifest).value ?: return null

                    val extraDependencies =
                        if (extractionState.isBundled) {
                            emptyList()
                        } else {
                            listOf(mapLibrary(ModuleLibraryIdentifier("net.msrandom", "side-annotations", "1.0.0", null)))
                        }

                    val library =
                        VariantMetadataHolder(
                            Dependency.DEFAULT_CONFIGURATION,
                            identifier,
                            Describables.of(identifier, "variant", Dependency.DEFAULT_CONFIGURATION),
                            collectLibraries(manifest, extractionState.libraries).common.map(Companion::mapLibrary) + extraDependencies,
                            listOf(artifact),
                            defaultAttributes(manifest, defaultAttributes).libraryAttributes(),
                            ImmutableCapabilities.EMPTY,
                            setOf(Dependency.DEFAULT_CONFIGURATION),
                        )

                    return ComponentMetadataHolder(
                        identifier,
                        attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                        DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, identifier.version),
                        listOf(library),
                        isChanging || requestMetaData.isChanging,
                        manifest.type,
                        versionList.latest.keys.reversed(),
                    )
                }

                MinecraftComponentResolvers.CLIENT_MODULE -> {
                    val extractionResult = extractionState(repository, manifest).value

                    val commonDependency =
                        extractionResult?.let {
                            MinecraftDependencyMetadataWrapper(
                                GradleDependencyMetadata(
                                    DefaultModuleComponentSelector.withAttributes(
                                        DefaultModuleComponentSelector.newSelector(
                                            DefaultModuleIdentifier.newId(
                                                MinecraftComponentResolvers.GROUP,
                                                MinecraftComponentResolvers.COMMON_MODULE,
                                            ),
                                            DefaultImmutableVersionConstraint("", "", identifier.version, emptyList(), null),
                                        ),
                                        attributesFactory.of(MappingsNamespace.attribute, objectFactory.named(MappingsNamespace.OBF)),
                                    ),
                                    emptyList(),
                                    false,
                                    true,
                                    null,
                                    true,
                                    null,
                                ),
                            )
                        }

                    val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())

                    val artifacts = listOf(artifact)

                    val variants =
                        libraries.client.asMap().map { (system, platformLibraries) ->
                            val osAttributes: ImmutableAttributes
                            val name: String
                            val dependencies: List<DependencyMetadata>

                            if (system == null) {
                                osAttributes = ImmutableAttributes.EMPTY
                                name = Dependency.DEFAULT_CONFIGURATION
                                dependencies = listOfNotNull(commonDependency) + platformLibraries.map(Companion::mapLibrary)
                            } else {
                                if (system.version == null) {
                                    osAttributes =
                                        ImmutableAttributes.EMPTY
                                            .addNamed(
                                                OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                                                DefaultOperatingSystem(system.name).toFamilyName(),
                                            )

                                    name = system.name.toString()
                                } else {
                                    val version = system.version.replace(Regex("[$^\\\\]"), "").replace('.', '-')

                                    osAttributes =
                                        ImmutableAttributes.EMPTY
                                            .addNamed(
                                                OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE,
                                                DefaultOperatingSystem(system.name).toFamilyName(),
                                            )
                                            .addNamed(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE, system.version)

                                    name = "${system.name}-$version"
                                }

                                dependencies = listOfNotNull(
                                    commonDependency,
                                ) + (libraries.client[null] + platformLibraries).map(Companion::mapLibrary)
                            }

                            VariantMetadataHolder(
                                name,
                                identifier,
                                Describables.of(identifier, "variant", name),
                                dependencies,
                                artifacts,
                                attributesFactory.concat(
                                    defaultAttributes(manifest, defaultAttributes).libraryAttributes(),
                                    osAttributes,
                                ),
                                ImmutableCapabilities.EMPTY,
                                setOf(name),
                            )
                        }

                    return ComponentMetadataHolder(
                        identifier,
                        attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                        DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, identifier.version),
                        variants,
                        isChanging || requestMetaData.isChanging,
                        manifest.type,
                        versionList.latest.keys.reversed(),
                    )
                }

                MinecraftComponentResolvers.CLIENT_NATIVES_MODULE -> {
                    val extractionResult = extractionState(repository, manifest).value
                    val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())

                    val variants =
                        libraries.natives.asMap().map { (system, platformLibraries) ->
                            val dependencies = mutableListOf<DependencyMetadata>()
                            val artifacts = mutableListOf<ComponentArtifactMetadata>()

                            for (platformLibrary in platformLibraries) {
                                dependencies.add(mapLibrary(platformLibrary.library))

                                val path =
                                    globalCacheDir.dir
                                        .toPath()
                                        .resolve(CodevCacheManager.ROOT_NAME)
                                        .resolve("native-extraction-rules")
                                        .resolve("${platformLibrary.hashCode().toString(16)}.json")

                                path.parent.createDirectories()

                                path.outputStream().use {
                                    Json.encodeToStream(platformLibrary, it)
                                }

                                artifacts.add(
                                    PublishArtifactLocalArtifactMetadata(
                                        identifier,
                                        ImmutablePublishArtifact(
                                            platformLibrary.library.module,
                                            "json",
                                            "json",
                                            platformLibrary.library.classifier,
                                            path.toFile(),
                                        ),
                                    ),
                                )
                            }

                            VariantMetadataHolder(
                                system.name.toString(),
                                identifier,
                                Describables.of(identifier, "variant", system.name.toString()),
                                dependencies,
                                artifacts,
                                defaultAttributes(
                                    manifest,
                                    defaultAttributes,
                                ).addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, system.name.toString()),
                                ImmutableCapabilities.EMPTY,
                                setOf(system.name.toString()),
                            )
                        }

                    return ComponentMetadataHolder(
                        identifier,
                        attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                        DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, identifier.version),
                        variants,
                        isChanging || requestMetaData.isChanging,
                        manifest.type,
                        versionList.latest.keys.reversed(),
                    )
                }

                else -> {
                    val fixedName = identifier.module.asMinecraftDownload()

                    if (fixedName != MinecraftComponentResolvers.SERVER_DOWNLOAD) {
                        val download = manifest.downloads[fixedName]

                        if (download != null) {
                            val extension = download.url.toString().substringAfterLast('.')

                            return ComponentMetadataHolder(
                                identifier,
                                attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                                DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, identifier.version),
                                listOf(
                                    VariantMetadataHolder(
                                        Dependency.DEFAULT_CONFIGURATION,
                                        identifier,
                                        Describables.of(identifier, "variant", Dependency.DEFAULT_CONFIGURATION),
                                        emptyList(),
                                        listOf(artifact(extension)),
                                        ImmutableAttributes.EMPTY,
                                        ImmutableCapabilities.EMPTY,
                                        setOf(Dependency.DEFAULT_CONFIGURATION),
                                    ),
                                ),
                                isChanging || requestMetaData.isChanging,
                                manifest.type,
                                versionList.latest.keys.reversed(),
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    private fun defaultAttributes(
        manifest: MinecraftVersionMetadata,
        defaultAttributes: ImmutableAttributes,
    ) = defaultAttributes(attributesFactory, instantiator, manifest, defaultAttributes)

    private fun ImmutableAttributes.libraryAttributes() = libraryAttributes(attributesFactory, instantiator)

    private fun ImmutableAttributes.addInt(
        attribute: Attribute<Int>,
        value: Int,
    ) = addInt(attributesFactory, attribute, value)

    private fun ImmutableAttributes.addNamed(
        attribute: Attribute<*>,
        value: String,
    ) = addNamed(attributesFactory, instantiator, attribute, value)

    companion object {
        private val manifestLock = Any()
        private var versionManifest: MinecraftVersionList? = null
        private var isManifestInitialized = false
        private val versionData = ConcurrentHashMap<String, MinecraftVersionMetadata?>()

        fun defaultAttributes(
            attributesFactory: ImmutableAttributesFactory,
            instantiator: NamedObjectInstantiator,
            manifest: MinecraftVersionMetadata,
            defaultAttributes: ImmutableAttributes,
        ) = defaultAttributes
            .addNamed(attributesFactory, instantiator, Bundling.BUNDLING_ATTRIBUTE, Bundling.EXTERNAL)
            .addNamed(
                attributesFactory,
                instantiator,
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                TargetJvmEnvironment.STANDARD_JVM,
            )
            .addInt(attributesFactory, TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, manifest.javaVersion.majorVersion)

        fun ImmutableAttributes.libraryAttributes(
            attributesFactory: ImmutableAttributesFactory,
            instantiator: NamedObjectInstantiator,
        ) = this
            .addNamed(attributesFactory, instantiator, Category.CATEGORY_ATTRIBUTE, Category.LIBRARY)
            .addNamed(attributesFactory, instantiator, LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, LibraryElements.JAR)

        fun ImmutableAttributes.addInt(
            attributesFactory: ImmutableAttributesFactory,
            attribute: Attribute<Int>,
            value: Int,
        ) = attributesFactory.concat(this, attribute, value)

        fun mapLibrary(library: ModuleLibraryIdentifier) =
            GradleDependencyMetadata(
                DefaultModuleComponentSelector.newSelector(
                    DefaultModuleIdentifier.newId(library.group, library.module),
                    library.version,
                ),
                emptyList(),
                false,
                false,
                null,
                false,
                library.classifier?.let {
                    DefaultIvyArtifactName(library.module, ArtifactTypeDefinition.JAR_TYPE, ArtifactTypeDefinition.JAR_TYPE, it)
                },
            )

        /**
         * @param manifest The version manifest for the version we're collecting libraries for
         * @param commonLibraries The libraries needed on the server, this information should be extracted from the server Jar.
         * @return The libraries to be used for generating metadata for the minecraft common and minecraft client dependencies
         */
        fun collectLibraries(
            manifest: MinecraftVersionMetadata,
            commonLibraries: Collection<ModuleLibraryIdentifier>,
        ): LibraryData {
            val client = HashMultimap.create<MinecraftVersionMetadata.Rule.OperatingSystem?, ModuleLibraryIdentifier>()
            val natives = HashMultimap.create<MinecraftVersionMetadata.Rule.OperatingSystem, LibraryData.Native>()

            for (library in manifest.libraries) {
                if (library.name in commonLibraries) continue

                if (library.natives.isEmpty()) {
                    if (library.rules.isEmpty()) {
                        client.put(null, library.name)
                    } else {
                        for (rule in library.rules) {
                            if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
                                client.put(rule.os, library.name)
                            }
                        }
                    }

                    continue
                }

                if (library.rules.isEmpty()) {
                    for ((key, classifierName) in library.natives) {
                        if (classifierName in library.downloads.classifiers) {
                            natives.put(
                                MinecraftVersionMetadata.Rule.OperatingSystem(key),
                                LibraryData.Native(library.name.copy(classifier = classifierName), library.extract),
                            )
                        }
                    }
                } else {
                    val iterator = library.rules.iterator()
                    while (iterator.hasNext()) {
                        val rule = iterator.next()
                        if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
                            if (rule.os == null) {
                                require(iterator.hasNext())

                                val next = iterator.next()

                                require(next.action == MinecraftVersionMetadata.RuleAction.Disallow && next.os != null)

                                for ((key, classifierName) in library.natives) {
                                    if (next.os.name != key) {
                                        natives.put(
                                            MinecraftVersionMetadata.Rule.OperatingSystem(key),
                                            LibraryData.Native(library.name.copy(classifier = classifierName), library.extract),
                                        )
                                    }
                                }
                            } else {
                                val classifierName = library.natives[rule.os.name]
                                if (classifierName in library.downloads.classifiers) {
                                    natives.put(
                                        rule.os,
                                        LibraryData.Native(library.name.copy(classifier = classifierName), library.extract),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            return LibraryData(commonLibraries, client, natives)
        }

        private fun checkCached(
            identifier: MinecraftComponentIdentifier,
            resolvedModuleVersion: ResolvedModuleVersion?,
            versionManifest: Path,
            timeProvider: BuildCommencedTimeProvider,
            cachePolicy: CachePolicy,
        ): MinecraftVersionList? {
            val list =
                versionManifest.inputStream().use {
                    json.decodeFromStream<MinecraftVersionManifest>(it)
                }

            val age = Duration.ofMillis(timeProvider.currentTime - versionManifest.getLastModifiedTime().toMillis())
            val moduleIdentifier = DefaultModuleIdentifier.newId(MinecraftComponentResolvers.GROUP, identifier.module)
            val expiry =
                if (resolvedModuleVersion == null) {
                    cachePolicy.versionListExpiry(
                        moduleIdentifier,
                        list.versions.mapTo(mutableSetOf()) {
                            DefaultModuleVersionIdentifier.newId(moduleIdentifier, it.id)
                        },
                        age,
                    )
                } else {
                    cachePolicy.moduleExpiry(
                        DefaultModuleComponentIdentifier.newId(moduleIdentifier, identifier.version),
                        resolvedModuleVersion,
                        age,
                    )
                }

            return if (expiry.isMustCheck) {
                null
            } else {
                MinecraftVersionList(list)
            }
        }

        private fun fetchVersionList(
            url: URI,
            resolvedModuleVersion: ResolvedModuleVersion?,
            result: BuildableModuleVersionListingResolveResult?,
            resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
            checksumService: ChecksumService,
            versionManifest: Path,
        ): MinecraftVersionList? {
            val location = ExternalResourceName(url)

            result?.attempted(location)

            val resource =
                if (resolvedModuleVersion == null) {
                    resourceAccessor.getResource(location, null, versionManifest, null)
                } else {
                    resourceAccessor.getResource(
                        location,
                        null,
                        versionManifest,
                        getAdditionalCandidates(versionManifest, checksumService),
                    )
                }

            return resource?.file?.let { file ->
                file.inputStream().use {
                    MinecraftVersionList(json.decodeFromStream(it))
                }
            }
        }

        fun getVersionList(
            id: MinecraftComponentIdentifier,
            resolvedModuleVersion: ResolvedModuleVersion?,
            url: URI,
            cacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            result: BuildableModuleVersionListingResolveResult?,
        ) = synchronized(manifestLock) {
            if (isManifestInitialized) {
                versionManifest
            } else {
                val versionManifest = cacheManager.rootPath.resolve("version-manifest.json")

                val cached =
                    versionManifest.takeIf(Path::exists)?.let {
                        checkCached(id, resolvedModuleVersion, it, timeProvider, cachePolicy)
                    }

                Companion.versionManifest = cached ?: fetchVersionList(
                    url,
                    resolvedModuleVersion,
                    result,
                    resourceAccessor,
                    checksumService,
                    versionManifest,
                )
                isManifestInitialized = true

                Companion.versionManifest
            }
        }

        fun getVersionManifest(
            id: MinecraftComponentIdentifier,
            url: URI,
            cacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            attempt: ((location: ExternalResourceName) -> Unit)?,
        ) = getVersionManifest(
            id,
            resourceAccessor,
            cachePolicy,
            cacheManager,
            checksumService,
            timeProvider,
            attempt,
            getVersionList(
                id,
                DefaultResolvedModuleVersion(
                    DefaultModuleVersionIdentifier.newId(MinecraftComponentResolvers.GROUP, id.module, id.version),
                ),
                url,
                cacheManager,
                cachePolicy,
                resourceAccessor,
                checksumService,
                timeProvider,
                null,
            ),
        )

        fun getVersionManifest(
            id: MinecraftComponentIdentifier,
            resourceAccessor: DefaultCacheAwareExternalResourceAccessor,
            cachePolicy: CachePolicy,
            cacheManager: CodevCacheManager,
            checksumService: ChecksumService,
            timeProvider: BuildCommencedTimeProvider,
            attempt: ((location: ExternalResourceName) -> Unit)?,
            versionList: MinecraftVersionList?,
        ): MinecraftVersionMetadata? =
            versionList?.versions?.get(id.version)?.let { info ->
                versionData.computeIfAbsent(id.version) {
                    val path =
                        cacheManager.rootPath
                            .resolve("cached-metadata")
                            .resolve(info.sha1!!)
                            .resolve("${id.version}.json")

                    val versionId = DefaultModuleVersionIdentifier.newId(MinecraftComponentResolvers.GROUP, id.module, id.version)
                    val resolvedVersion = DefaultResolvedModuleVersion(versionId)
                    val refresh =
                        !path.exists() ||
                            cachePolicy.moduleExpiry(
                                DefaultModuleComponentIdentifier.newId(versionId),
                                resolvedVersion,
                                Duration.ofMillis(timeProvider.currentTime - path.getLastModifiedTime().toMillis()),
                            ).isMustCheck
                    if (!refresh && checksumService.sha1(path.toFile()).toString() == info.sha1) {
                        path.inputStream()
                    } else {
                        val location = ExternalResourceName(info.url)
                        attempt?.invoke(location)

                        resourceAccessor.getResource(
                            location,
                            info.sha1,
                            path,
                            getAdditionalCandidates(path, checksumService),
                        )?.file?.inputStream()
                    }?.use(json::decodeFromStream)
                }
            }

        fun extractionState(
            repository: MinecraftRepositoryImpl.Resolver,
            manifest: MinecraftVersionMetadata,
            cacheManager: CodevCacheManager,
            buildOperationExecutor: BuildOperationExecutor,
            checksumService: ChecksumService,
        ) = getExtractionState(cacheManager, buildOperationExecutor, manifest, {
            resolveMojangFile(
                manifest,
                cacheManager,
                checksumService,
                repository,
                MinecraftComponentResolvers.SERVER_DOWNLOAD,
            )
        }) {
            resolveMojangFile(
                manifest,
                cacheManager,
                checksumService,
                repository,
                MinecraftComponentResolvers.CLIENT_DOWNLOAD,
            )!!
        }
    }
}
