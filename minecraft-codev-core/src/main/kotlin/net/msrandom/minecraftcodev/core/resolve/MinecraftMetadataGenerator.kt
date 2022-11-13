package net.msrandom.minecraftcodev.core.resolve

import com.google.common.collect.HashMultimap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import net.msrandom.minecraftcodev.core.*
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.addConfigurationResolutionDependencies
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyMetadataWrapper
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.resolveMojangFile
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.addNamed
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers.Companion.asMinecraftDownload
import net.msrandom.minecraftcodev.gradle.CodevGradleLinkageLoader
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ResolvedModuleVersion
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.*
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.configurations.dynamicversion.CachePolicy
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ivyservice.modulecache.FileStoreAndIndexProvider
import org.gradle.api.internal.artifacts.ivyservice.modulecache.dynamicversions.DefaultResolvedModuleVersion
import org.gradle.api.internal.artifacts.publish.ImmutablePublishArtifact
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.internal.attributes.ImmutableAttributesFactory
import org.gradle.api.internal.model.NamedObjectInstantiator
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.AbstractTaskDependency
import org.gradle.api.internal.tasks.TaskDependencyResolveContext
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.initialization.layout.GlobalCacheDir
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector
import org.gradle.internal.component.external.model.GradleDependencyMetadata
import org.gradle.internal.component.external.model.ImmutableCapabilities
import org.gradle.internal.component.local.model.PublishArtifactLocalArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.hash.ChecksumService
import org.gradle.internal.operations.BuildOperationExecutor
import org.gradle.internal.resolve.result.BuildableComponentResolveResult
import org.gradle.internal.resolve.result.BuildableModuleVersionListingResolveResult
import org.gradle.internal.resource.ExternalResourceName
import org.gradle.internal.resource.transfer.CacheAwareExternalResourceAccessor
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.io.path.*

open class MinecraftMetadataGenerator @Inject constructor(
    private val cacheManager: CodevCacheManager,
    private val globalCacheDir: GlobalCacheDir,
    private val attributesFactory: ImmutableAttributesFactory,
    private val buildOperationExecutor: BuildOperationExecutor,
    private val fileStoreAndIndexProvider: FileStoreAndIndexProvider,
    private val instantiator: NamedObjectInstantiator,
    private val objectFactory: ObjectFactory,
    private val checksumService: ChecksumService,
    private val cachePolicy: CachePolicy,
    private val project: Project
) {
    private fun extractionState(
        componentIdentifier: ModuleComponentIdentifier,
        repository: MinecraftRepositoryImpl.Resolver,
        manifest: MinecraftVersionMetadata
    ): Lazy<ServerExtractionResult>? {
        val shouldRefresh = { _: File, duration: Duration ->
            cachePolicy.moduleExpiry(
                componentIdentifier,
                DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(componentIdentifier)),
                duration
            ).isMustCheck
        }

        val serverJar = resolveMojangFile(
            manifest,
            cacheManager,
            checksumService,
            repository,
            MinecraftComponentResolvers.SERVER_DOWNLOAD,
            shouldRefresh
        )

        return if (serverJar == null) {
            null
        } else {
            getExtractionState(buildOperationExecutor, manifest, serverJar) {
                resolveMojangFile(
                    manifest,
                    cacheManager,
                    checksumService,
                    repository,
                    MinecraftComponentResolvers.CLIENT_DOWNLOAD,
                    shouldRefresh
                )!!
            }
        }
    }

    fun resolveMetadata(
        repository: MinecraftRepositoryImpl.Resolver,
        extraLibraries: List<ModuleLibraryIdentifier>,
        resourceAccessor: CacheAwareExternalResourceAccessor,
        moduleComponentIdentifier: ModuleComponentIdentifier,
        requestMetaData: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult,
        mappingsNamespace: String,
        configuration: Configuration
    ) {
        val versionList = getVersionList(
            moduleComponentIdentifier,
            DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier)),
            repository.url,
            cacheManager,
            cachePolicy,
            resourceAccessor,
            fileStoreAndIndexProvider,
            null
        )

        val manifest = getVersionManifest(
            moduleComponentIdentifier,
            resourceAccessor,
            fileStoreAndIndexProvider,
            result::attempted,
            versionList
        )

        if (versionList != null && manifest != null) {
            fun artifact(extension: String, classifier: String? = null) = object : DefaultModuleComponentArtifactMetadata(
                moduleComponentIdentifier,
                DefaultIvyArtifactName(
                    moduleComponentIdentifier.module,
                    extension,
                    extension,
                    classifier
                )
            ) {
/*                override fun getBuildDependencies() = object : AbstractTaskDependency() {
                    override fun visitDependencies(context: TaskDependencyResolveContext) =
                        project.addConfigurationResolutionDependencies(context, configuration)
                }*/
            }

            val defaultAttributes = ImmutableAttributes.EMPTY.addNamed(MappingsNamespace.attribute, mappingsNamespace)

            val extractionResult = extractionState(
                moduleComponentIdentifier,
                repository,
                manifest
            )?.value

            val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())

            val artifacts = listOf(artifact(ArtifactTypeDefinition.JAR_TYPE))

            val variants = mutableListOf<ConfigurationMetadata>()

            for ((system, platformLibraries) in libraries.client.asMap()) {
                val osAttributes: ImmutableAttributes
                val name: String
                val dependencies: List<DependencyMetadata>

                if (system == null) {
                    osAttributes = ImmutableAttributes.EMPTY
                    name = Dependency.DEFAULT_CONFIGURATION
                    dependencies = (libraries.common + extraLibraries + platformLibraries).map(::mapLibrary)
                } else {
                    if (system.version == null) {
                        osAttributes = ImmutableAttributes.EMPTY
                            .addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, DefaultOperatingSystem(system.name).toFamilyName())

                        name = system.name.toString()
                    } else {
                        val version = system.version.replace(Regex("[$^\\\\]"), "").replace('.', '-')

                        osAttributes = ImmutableAttributes.EMPTY
                            .addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, DefaultOperatingSystem(system.name).toFamilyName())
                            .addNamed(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE, system.version)

                        name = "${system.name}-${version}"
                    }

                    dependencies = (libraries.common + extraLibraries + libraries.client[null] + platformLibraries).map(::mapLibrary)
                }

                variants.add(
                    CodevGradleLinkageLoader.ConfigurationMetadata(
                        name,
                        moduleComponentIdentifier,
                        dependencies,
                        artifacts,
                        attributesFactory.concat(
                            defaultAttributes(manifest, defaultAttributes).libraryAttributes(),
                            osAttributes
                        ),
                        ImmutableCapabilities.EMPTY,
                        setOf(name),
                        objectFactory
                    )
                )
            }

            variants.add(
                CodevGradleLinkageLoader.ConfigurationMetadata(
                    JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME,
                    moduleComponentIdentifier,
                    emptyList(),
                    listOf(artifact(ArtifactTypeDefinition.JAR_TYPE, "sources")),
                    defaultAttributes(manifest, defaultAttributes).runtimeAttributes().docsAttributes(DocsType.SOURCES),
                    ImmutableCapabilities.EMPTY,
                    setOf(JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME),
                    objectFactory
                )
            )

            result.resolved(
                CodevGradleLinkageLoader.ComponentResolveMetadata(
                    attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                    moduleComponentIdentifier,
                    DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                    variants,
                    requestMetaData.isChanging,
                    manifest.type,
                    versionList.latest.keys.reversed(),
                    objectFactory
                )
            )

            return
        }
    }

    fun resolveMetadata(
        repository: MinecraftRepositoryImpl.Resolver,
        resourceAccessor: CacheAwareExternalResourceAccessor,
        moduleComponentIdentifier: ModuleComponentIdentifier,
        requestMetaData: ComponentOverrideMetadata,
        result: BuildableComponentResolveResult
    ) {
        val versionList = getVersionList(
            moduleComponentIdentifier,
            DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier)),
            repository.url,
            cacheManager,
            cachePolicy,
            resourceAccessor,
            fileStoreAndIndexProvider,
            null
        )

        val manifest = getVersionManifest(
            moduleComponentIdentifier,
            resourceAccessor,
            fileStoreAndIndexProvider,
            result::attempted,
            versionList
        )

        if (versionList != null && manifest != null) {
            fun artifact(extension: String, classifier: String? = null) = DefaultModuleComponentArtifactMetadata(
                moduleComponentIdentifier,
                DefaultIvyArtifactName(
                    moduleComponentIdentifier.module,
                    extension,
                    extension,
                    classifier
                )
            )

            val defaultAttributes = ImmutableAttributes.EMPTY.addNamed(MappingsNamespace.attribute, MappingsNamespace.OBF)
            when (moduleComponentIdentifier.module) {
                MinecraftComponentResolvers.COMMON_MODULE -> {
                    val extractionState = extractionState(
                        moduleComponentIdentifier,
                        repository,
                        manifest
                    ) ?: return

                    val library = CodevGradleLinkageLoader.ConfigurationMetadata(
                        Dependency.DEFAULT_CONFIGURATION,
                        moduleComponentIdentifier,
                        collectLibraries(manifest, extractionState.value.libraries).common.map(::mapLibrary),
                        listOf(artifact(ArtifactTypeDefinition.JAR_TYPE)),
                        defaultAttributes(manifest, defaultAttributes).libraryAttributes().runtimeAttributes(),
                        ImmutableCapabilities.EMPTY,
                        setOf(Dependency.DEFAULT_CONFIGURATION),
                        objectFactory
                    )

                    val sourcesElements = CodevGradleLinkageLoader.ConfigurationMetadata(
                        JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME,
                        moduleComponentIdentifier,
                        emptyList(),
                        listOf(artifact(ArtifactTypeDefinition.JAR_TYPE, "sources")),
                        defaultAttributes(manifest, defaultAttributes).runtimeAttributes().docsAttributes(DocsType.SOURCES),
                        ImmutableCapabilities.EMPTY,
                        setOf(JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME),
                        objectFactory
                    )

                    result.resolved(
                        CodevGradleLinkageLoader.ComponentResolveMetadata(
                            attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                            moduleComponentIdentifier,
                            DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                            listOf(library, sourcesElements),
                            requestMetaData.isChanging,
                            manifest.type,
                            versionList.latest.keys.reversed(),
                            objectFactory
                        )
                    )
                    return
                }

                MinecraftComponentResolvers.CLIENT_MODULE -> {
                    val extractionResult = extractionState(
                        moduleComponentIdentifier,
                        repository,
                        manifest
                    )?.value

                    val commonDependency = extractionResult?.let {
                        MinecraftDependencyMetadataWrapper(
                            GradleDependencyMetadata(
                                DefaultModuleComponentSelector.withAttributes(
                                    DefaultModuleComponentSelector.newSelector(
                                        DefaultModuleIdentifier.newId(MinecraftComponentResolvers.GROUP, MinecraftComponentResolvers.COMMON_MODULE),
                                        DefaultImmutableVersionConstraint("", "", moduleComponentIdentifier.version, emptyList(), null)
                                    ),
                                    attributesFactory.of(MappingsNamespace.attribute, objectFactory.named(MappingsNamespace.OBF))
                                ),
                                emptyList(),
                                false,
                                true,
                                null,
                                true,
                                null
                            ),
                        )
                    }

                    val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())

                    val artifacts = listOf(artifact(ArtifactTypeDefinition.JAR_TYPE))

                    val variants = mutableListOf<ConfigurationMetadata>()

                    for ((system, platformLibraries) in libraries.client.asMap()) {
                        val osAttributes: ImmutableAttributes
                        val name: String
                        val dependencies: List<DependencyMetadata>

                        if (system == null) {
                            osAttributes = ImmutableAttributes.EMPTY
                            name = Dependency.DEFAULT_CONFIGURATION
                            dependencies = listOfNotNull(commonDependency) + platformLibraries.map(::mapLibrary)
                        } else {
                            if (system.version == null) {
                                osAttributes = ImmutableAttributes.EMPTY
                                    .addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, DefaultOperatingSystem(system.name).toFamilyName())

                                name = system.name.toString()
                            } else {
                                val version = system.version.replace(Regex("[$^\\\\]"), "").replace('.', '-')

                                osAttributes = ImmutableAttributes.EMPTY
                                    .addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, DefaultOperatingSystem(system.name).toFamilyName())
                                    .addNamed(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE, system.version)

                                name = "${system.name}-${version}"
                            }

                            dependencies = listOfNotNull(commonDependency) + (libraries.client[null] + platformLibraries).map(::mapLibrary)
                        }

                        variants.add(
                            CodevGradleLinkageLoader.ConfigurationMetadata(
                                name,
                                moduleComponentIdentifier,
                                dependencies,
                                artifacts,
                                attributesFactory.concat(
                                    defaultAttributes(manifest, defaultAttributes).libraryAttributes(),
                                    osAttributes
                                ),
                                ImmutableCapabilities.EMPTY,
                                setOf(name),
                                objectFactory
                            )
                        )
                    }

                    variants.add(
                        CodevGradleLinkageLoader.ConfigurationMetadata(
                            JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME,
                            moduleComponentIdentifier,
                            emptyList(),
                            listOf(artifact(ArtifactTypeDefinition.JAR_TYPE, "sources")),
                            defaultAttributes(manifest, defaultAttributes).runtimeAttributes().docsAttributes(DocsType.SOURCES),
                            ImmutableCapabilities.EMPTY,
                            setOf(JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME),
                            objectFactory
                        )
                    )

                    result.resolved(
                        CodevGradleLinkageLoader.ComponentResolveMetadata(
                            attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                            moduleComponentIdentifier,
                            DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                            variants,
                            requestMetaData.isChanging,
                            manifest.type,
                            versionList.latest.keys.reversed(),
                            objectFactory
                        )
                    )

                    return
                }

                MinecraftComponentResolvers.CLIENT_NATIVES_MODULE -> {
                    val extractionResult = extractionState(
                        moduleComponentIdentifier,
                        repository,
                        manifest
                    )?.value

                    val libraries = collectLibraries(manifest, extractionResult?.libraries ?: emptyList())
                    val variants = mutableListOf<ConfigurationMetadata>()

                    for ((system, platformLibraries) in libraries.natives.asMap()) {
                        val dependencies = mutableListOf<DependencyMetadata>()
                        val artifacts = mutableListOf<ComponentArtifactMetadata>()

                        for (platformLibrary in platformLibraries) {
                            dependencies.add(mapLibrary(platformLibrary.library))

                            val path = globalCacheDir.dir
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
                                    moduleComponentIdentifier,
                                    ImmutablePublishArtifact(
                                        platformLibrary.library.module,
                                        "json",
                                        "json",
                                        platformLibrary.library.classifier,
                                        path.toFile()
                                    )
                                )
                            )
                        }

                        variants.add(
                            CodevGradleLinkageLoader.ConfigurationMetadata(
                                system.name.toString(),
                                moduleComponentIdentifier,
                                dependencies,
                                artifacts,
                                defaultAttributes(manifest, defaultAttributes).runtimeAttributes().addNamed(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, system.name.toString()),
                                ImmutableCapabilities.EMPTY,
                                setOf(system.name.toString()),
                                objectFactory
                            )
                        )
                    }

                    result.resolved(
                        CodevGradleLinkageLoader.ComponentResolveMetadata(
                            attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                            moduleComponentIdentifier,
                            DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                            variants,
                            requestMetaData.isChanging,
                            manifest.type,
                            versionList.latest.keys.reversed(),
                            objectFactory
                        )
                    )
                }

                else -> {
                    val fixedName = moduleComponentIdentifier.module.asMinecraftDownload()

                    if (fixedName != MinecraftComponentResolvers.SERVER_DOWNLOAD) {
                        val download = manifest.downloads[fixedName]

                        if (download != null) {
                            val extension = download.url.toString().substringAfterLast('.')

                            result.resolved(
                                CodevGradleLinkageLoader.ComponentResolveMetadata(
                                    attributesFactory.of(ProjectInternal.STATUS_ATTRIBUTE, manifest.type),
                                    moduleComponentIdentifier,
                                    DefaultModuleVersionIdentifier.newId(moduleComponentIdentifier.moduleIdentifier, moduleComponentIdentifier.version),
                                    listOf(
                                        CodevGradleLinkageLoader.ConfigurationMetadata(
                                            Dependency.DEFAULT_CONFIGURATION,
                                            moduleComponentIdentifier,
                                            emptyList(),
                                            listOf(artifact(extension)),
                                            ImmutableAttributes.EMPTY,
                                            ImmutableCapabilities.EMPTY,
                                            setOf(Dependency.DEFAULT_CONFIGURATION),
                                            objectFactory
                                        )
                                    ),
                                    requestMetaData.isChanging,
                                    manifest.type,
                                    versionList.latest.keys.reversed(),
                                    objectFactory
                                )
                            )

                            return
                        }
                    }
                }
            }
        }
    }

    private fun mapLibrary(library: ModuleLibraryIdentifier) = GradleDependencyMetadata(
        DefaultModuleComponentSelector.newSelector(DefaultModuleIdentifier.newId(library.group, library.module), library.version),
        emptyList(),
        false,
        false,
        null,
        false,
        library.classifier?.let {
            DefaultIvyArtifactName(library.module, ArtifactTypeDefinition.JAR_TYPE, ArtifactTypeDefinition.JAR_TYPE, it)
        }
    )

    private fun defaultAttributes(
        manifest: MinecraftVersionMetadata,
        defaultAttributes: ImmutableAttributes
    ) = defaultAttributes
        .addNamed(Bundling.BUNDLING_ATTRIBUTE, Bundling.EXTERNAL)
        .addNamed(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, TargetJvmEnvironment.STANDARD_JVM)
        .addInt(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, manifest.javaVersion.majorVersion)

    private fun ImmutableAttributes.libraryAttributes() = this
        .addNamed(Category.CATEGORY_ATTRIBUTE, Category.LIBRARY)
        .addNamed(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, LibraryElements.JAR)

    private fun ImmutableAttributes.docsAttributes(docsType: String) = this
        .addNamed(Category.CATEGORY_ATTRIBUTE, Category.DOCUMENTATION)
        .addNamed(DocsType.DOCS_TYPE_ATTRIBUTE, docsType)

    private fun ImmutableAttributes.apiAttributes() =
        addNamed(Usage.USAGE_ATTRIBUTE, Usage.JAVA_API)

    private fun ImmutableAttributes.runtimeAttributes() =
        addNamed(Usage.USAGE_ATTRIBUTE, Usage.JAVA_RUNTIME)

    private fun ImmutableAttributes.addInt(attribute: Attribute<Int>, value: Int) =
        attributesFactory.concat(this, attribute, value)

    private fun ImmutableAttributes.addNamed(attribute: Attribute<*>, value: String) =
        addNamed(attributesFactory, instantiator, attribute, value)

    companion object {
        private val manifestLock = Any()
        private var versionManifest: MinecraftVersionList? = null
        private var isManifestInitialized = false
        private val versionData = ConcurrentHashMap<String, MinecraftVersionMetadata?>()

        /**
         * @param manifest The version manifest for the version we're collecting libraries for
         * @param commonLibraries The libraries needed on the server, this information should be extracted from the server Jar.
         * @return The libraries to be used for generating metadata for the minecraft common and minecraft client dependencies
         */
        private fun collectLibraries(manifest: MinecraftVersionMetadata, commonLibraries: Collection<ModuleLibraryIdentifier>): LibraryData {
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
                            natives.put(MinecraftVersionMetadata.Rule.OperatingSystem(key), LibraryData.Native(library.name.copy(classifier = classifierName), library.extract))
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
                                        natives.put(MinecraftVersionMetadata.Rule.OperatingSystem(key), LibraryData.Native(library.name.copy(classifier = classifierName), library.extract))
                                    }
                                }
                            } else {
                                val classifierName = library.natives[rule.os.name]
                                if (classifierName in library.downloads.classifiers) {
                                    natives.put(rule.os, LibraryData.Native(library.name.copy(classifier = classifierName), library.extract))
                                }
                            }
                        }
                    }
                }
            }

            return LibraryData(commonLibraries, client, natives)
        }

        private fun checkCached(
            identifier: ModuleComponentIdentifier,
            resolvedModuleVersion: ResolvedModuleVersion?,
            versionManifest: Path,
            cachePolicy: CachePolicy
        ): MinecraftVersionList? {
            val list = versionManifest.inputStream().use {
                json.decodeFromStream<MinecraftVersionManifest>(it)
            }

            val age = Duration.between(versionManifest.getLastModifiedTime().toInstant(), Instant.now())
            val expiry = if (resolvedModuleVersion == null) {
                cachePolicy.versionListExpiry(identifier.moduleIdentifier, list.versions.mapTo(mutableSetOf()) { DefaultModuleVersionIdentifier.newId(identifier.moduleIdentifier, it.id) }, age)
            } else {
                cachePolicy.moduleExpiry(identifier, resolvedModuleVersion, age)
            }

            return if (expiry.isMustCheck) {
                null
            } else {
                MinecraftVersionList(list)
            }
        }

        private fun fetchVersionList(
            url: URI,
            fileStoreAndIndexProvider: FileStoreAndIndexProvider,
            result: BuildableModuleVersionListingResolveResult?,
            resourceAccessor: CacheAwareExternalResourceAccessor,
            versionManifest: Path
        ): MinecraftVersionList? {
            val location = ExternalResourceName(url)

            val fileStore = object : CacheAwareExternalResourceAccessor.DefaultResourceFileStore<String>(fileStoreAndIndexProvider.externalResourceFileStore) {
                override fun computeKey() = location.toString()
            }

            result?.attempted(location)

            val resource = resourceAccessor.getResource(location, null, fileStore, null)

            return resource?.file?.let { file ->
                versionManifest.deleteIfExists()
                versionManifest.parent.createDirectories()

                file.toPath().copyTo(versionManifest)

                file.inputStream().use {
                    MinecraftVersionList(json.decodeFromStream(it))
                }
            }
        }

        fun getVersionList(
            id: ModuleComponentIdentifier,
            resolvedModuleVersion: ResolvedModuleVersion?,
            url: URI,
            cacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            resourceAccessor: CacheAwareExternalResourceAccessor,
            fileStoreAndIndexProvider: FileStoreAndIndexProvider,
            result: BuildableModuleVersionListingResolveResult?
        ) = synchronized(manifestLock) {
            if (isManifestInitialized) {
                versionManifest
            } else {
                val versionManifest = cacheManager.rootPath.resolve("version-manifest.json")

                val cached = versionManifest.takeIf(Path::exists)?.let { checkCached(id, resolvedModuleVersion, it, cachePolicy) }

                this.versionManifest = cached ?: fetchVersionList(url, fileStoreAndIndexProvider, result, resourceAccessor, versionManifest)
                this.isManifestInitialized = true

                this.versionManifest
            }
        }

        fun getVersionManifest(
            id: ModuleComponentIdentifier,
            url: URI,
            cacheManager: CodevCacheManager,
            cachePolicy: CachePolicy,
            resourceAccessor: CacheAwareExternalResourceAccessor,
            fileStoreAndIndexProvider: FileStoreAndIndexProvider,
            attempt: ((location: ExternalResourceName) -> Unit)?,
        ) = getVersionManifest(
            id,
            resourceAccessor,
            fileStoreAndIndexProvider,
            attempt,
            getVersionList(id, DefaultResolvedModuleVersion(DefaultModuleVersionIdentifier.newId(id)), url, cacheManager, cachePolicy, resourceAccessor, fileStoreAndIndexProvider, null)
        )

        fun getVersionManifest(
            id: ModuleComponentIdentifier,
            resourceAccessor: CacheAwareExternalResourceAccessor,
            fileStoreAndIndexProvider: FileStoreAndIndexProvider,
            attempt: ((location: ExternalResourceName) -> Unit)?,
            versionList: MinecraftVersionList?
        ): MinecraftVersionMetadata? = versionList?.versions?.get(id.version)?.let { info ->
            versionData.computeIfAbsent(id.version) {
                val location = ExternalResourceName(info.url)

                attempt?.invoke(location)

                resourceAccessor.getResource(
                    location,
                    null,
                    object : CacheAwareExternalResourceAccessor.DefaultResourceFileStore<String>(fileStoreAndIndexProvider.externalResourceFileStore) {
                        override fun computeKey() = location.toString()
                    },
                    null
                )?.file?.inputStream()?.use(json::decodeFromStream)
            }
        }
    }
}
