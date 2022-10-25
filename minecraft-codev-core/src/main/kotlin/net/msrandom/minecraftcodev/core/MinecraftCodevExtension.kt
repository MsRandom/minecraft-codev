package net.msrandom.minecraftcodev.core

import kotlinx.serialization.json.Json
import net.msrandom.minecraftcodev.core.runs.MinecraftRunConfigurationBuilder
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.*
import org.gradle.api.plugins.ExtensionAware
import org.gradle.initialization.layout.GlobalCacheDir
import org.gradle.internal.impldep.org.apache.commons.lang.SystemUtils
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream

abstract class MinecraftCodevExtension @Inject constructor(
    project: Project,
    cacheDir: GlobalCacheDir
) : ExtensionAware {
    val cache: Path = cacheDir.dir.toPath().resolve("minecraft-codev")
    val assets: Path = cache.resolve("assets")
    val resources: Path = cache.resolve("resources")
/*
    @Deprecated("Should use gradle file store")
    val downloadCache: Path = cache.resolve("download_cache")

    val generatedRepository: Path by lazy {
        val base = cache.resolve("generated_repositories")
        val forgeUserdev = project.configurations.getByName(PatcherExtension.PATCHES_CONFIGURATION).resolvedConfiguration.resolvedArtifacts.firstOrNull()?.moduleVersion?.id

        if (forgeUserdev == null) {
            base.resolve("vanilla")
        } else {
            base
                .resolve("${forgeUserdev.group}:${forgeUserdev.name}")
                .resolve(forgeUserdev.version)
        }
    }*/

    val logging: Path = cache.resolve("logging")
/*

    val remapper = Remapper(project)

    private val versionManifest = downloadCache.resolve("version_manifest.json")
*/
/*

    internal var versionInfo = manifestDownload()
        private set

    internal var byVersion = lazy { versionInfo.value.versions.associateBy(MinecraftVersionManifest.VersionInfo::id) }
        private set

    private val versionCache = hashMapOf<String, MinecraftVersionMetadata>()
*/

    val runs = project.container(MinecraftRunConfigurationBuilder::class.java)

    init {
        project.configurations.create(MinecraftCodevPlugin.ACCESS_WIDENERS) {
            it.isCanBeConsumed = false
        }

        project.dependencies.attributesSchema { schema ->
            schema.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE) {
                it.disambiguationRules.add(OperatingSystemDisambiguationRule::class.java)
            }

            schema.attribute(MinecraftCodevPlugin.OPERATING_SYSTEM_VERSION_PATTERN_ATTRIBUTE) {
                it.compatibilityRules.add(VersionPatternCompatibilityRule::class.java)
            }

            schema.attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE)
        }

        project.dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE) {
            it.attributes.attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE, false)
        }

        project.configurations.all { configuration ->
            configuration.attributes {
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.named(OperatingSystem.current().familyName))
            }
        }

/*        project.dependencies.registerTransform(AccessWidenTransformAction::class.java) { spec ->
            spec.from.attribute(ARTIFACT_TYPE, ArtifactTypeDefinition.JAR_TYPE).attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE, false)
            spec.to.attribute(ARTIFACT_TYPE, ArtifactTypeDefinition.JAR_TYPE).attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE, true)

            spec.parameters {
                it.accessWideners.from(project.configurations.named(MinecraftCodevPlugin.ACCESS_WIDENERS))
            }
        }

        var clientTasksSetup = false

        apply {
            project.configurations.all { configuration ->
                if (configuration.name == MinecraftCodevPlugin.ACCESS_WIDENERS || configuration.name == PatcherExtension.PATCHES_CONFIGURATION) return@all

                configuration.allDependencies.matching {
                    // TODO resolve instead of using dependency version
                    it.group == MinecraftRepositoryAccess.GROUP && it.name == MinecraftRepositoryAccess.CLIENT && it.version != null
                }.all { dependency ->
                    if (!clientTasksSetup) {
                        clientTasksSetup = true

                        val manifest = versionData(dependency.version!!)

                        if (manifest.libraries.any { it.natives.isNotEmpty() }) {
                            project.dependencies.add(MinecraftMetadataGenerator.NATIVES, "${MinecraftRepositoryAccess.GROUP}:${MinecraftMetadataGenerator.NATIVES}:${dependency.version}")
                        }

                        project.tasks.named(MinecraftCodevPlugin.DOWNLOAD_ASSETS, DownloadAssets::class.java) {
                            it.assetIndexes.add(manifest.assetIndex)
                        }
                    }
                }

                configuration.decorate(project)
            }
        }*/

        // generatedRepositoryPath = ::generatedRepository
    }
/*

    private fun manifestDownload() = lazy {
        val input = downloadToFile(versionManifest) {
            URL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
        }

        input.use {
            json.decodeFromStream<MinecraftVersionManifest>(it)
        }
    }
*/

    /*    @Deprecated("Should use the raw repository")
        fun versionData(version: String) = versionCache.computeIfAbsent(version) {
            val input = downloadToFile(downloadCache.resolve(version).resolve("data.json")) {
                val info = byVersion.value[version] ?: run {
                    // Redownload in case we have an outdated manifest
                    versionManifest.deleteExisting()
                    versionInfo = manifestDownload()

                    byVersion = lazy {
                        versionInfo.value.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
                    }

                    byVersion.value[version] ?: throw IllegalArgumentException("Invalid Minecraft version: $version")
                }
                info.url.toURL()
            }

            input.use(json::decodeFromStream)
        }

        fun javaVersion(minecraftVersion: String) = JavaVersion.toVersion(versionData(minecraftVersion).javaVersion.majorVersion)

        fun isNotMappedRepository(repository: ArtifactRepository) = repository !is MavenArtifactRepository || repository.url != remapper.mappedRepository.toUri()
    */
    companion object {
        internal lateinit var generatedRepositoryPath: () -> Path

        val json = Json {
            ignoreUnknownKeys = true
        }

        internal fun osVersion(): String {
            val version = SystemUtils.OS_VERSION
            val versionEnd = version.indexOf('-')
            return if (versionEnd < 0) version else version.substring(0, versionEnd)
        }

        internal fun downloadToFile(path: Path, url: () -> URL): InputStream {
            val input = if (path.exists()) {
                path.inputStream()
            } else {
                val stream = url().openStream().buffered()
                try {
                    stream.mark(Int.MAX_VALUE)
                    path.parent.createDirectories()
                    Files.copy(stream, path)
                    stream.reset()
                } catch (exception: IOException) {
                    stream.close()
                    throw exception
                }
                stream
            }

            return input
        }

        private fun <T> Configuration.getAttribute(dependency: ExternalModuleDependency, attribute: Attribute<T>): T? {
            var value = dependency.attributes.getAttribute(attribute)

            if (value == null) {
                value = dependencyConstraints
                    .firstOrNull { it.group == dependency.group && it.name == dependency.name }
                    ?.attributes?.getAttribute(attribute)

                if (value == null) {
                    // If we can't find a value for the attribute by this point, it probably is not set
                    value = attributes.getAttribute(attribute)
                }
            }

            return value
        }

        @JvmStatic
        fun Configuration.decorate(project: Project, withRemap: Boolean = true): Configuration {
            attributes {
                it.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, project.named(OperatingSystem.current().familyName))
                it.attribute(MinecraftCodevPlugin.ACCESS_WIDENED_ATTRIBUTE, true)
            }

            withDependencies { dependencies ->
                val extension = project.minecraftCodev

                /*var hasRemap = false
                for (dependency in dependencies) {
                    if (dependency is ExternalModuleDependency) {
                        if (dependency.group == MinecraftRepositoryAccess.GROUP) {
                            val fileStore = project.serviceOf<FileStoreAndIndexProvider>().artifactIdentifierFileStore
                            // TODO select version the way Gradle normally would rather than just using the string
                            if (getAttribute(dependency, PatcherExtension.PATCHED_ATTRIBUTE) == true) {
                                dependency.version {
                                    it.require(PatchedMinecraftRepositoryAccess.setupFiles(project, dependency.name, dependency.version, extension.remapper, fileStore))
                                }
                            } else {
                                MinecraftRepositoryAccess.setupFiles(project, dependency.name, dependency.version, fileStore)?.let { version ->
                                    dependency.version {
                                        it.require(version)
                                    }
                                }
                            }
                        }

                        if (withRemap) {
                            val targetNamespace = getAttribute(dependency, Remapper.MAPPINGS_ATTRIBUTE)

                            // We know this dependency is meant to be remapped if we have a target namespace
                            if (targetNamespace != null) {
                                val sourceNamespace = getAttribute(dependency, Remapper.SOURCE_MAPPINGS_ATTRIBUTE)

                                MappedDependency.mapDependency(
                                    project,
                                    extension.attributesFactory,
                                    extension.instantiator,
                                    this,
                                    sourceNamespace,
                                    targetNamespace,
                                    dependency,
                                    extension.remapper
                                )

                                hasRemap = true
                            }
                        }
                    }
                }

                if (hasRemap) {
                    if (project.repositories.none { !extension.isNotMappedRepository(it) }) {
                        project.repositories.maven { maven ->
                            maven.name = "Mapped Repository"
                            maven.url = extension.remapper.mappedRepository.toUri()
                            maven.metadataSources(MavenArtifactRepository.MetadataSources::gradleMetadata)
                        }

                        project.repositories.add(0, project.repositories.removeLast())
                    }
                }*/
            }

            return this
        }
    }

    class OperatingSystemDisambiguationRule : AttributeDisambiguationRule<OperatingSystemFamily?> {
        override fun execute(details: MultipleCandidatesDetails<OperatingSystemFamily?>) {
            val consumerValue = details.consumerValue?.name

            if (consumerValue == null && null in details.candidateValues) {
                return details.closestMatch(null)
            }

            val effectiveConsumer = consumerValue ?: DefaultNativePlatform.host().operatingSystem.toFamilyName()

            val bestMatch = details.candidateValues.firstOrNull { it != null && it.name == effectiveConsumer }
            if (bestMatch != null) {
                return details.closestMatch(bestMatch)
            } else {
                if (null in details.candidateValues) {
                    return details.closestMatch(null)
                }
            }
        }
    }

    class VersionPatternCompatibilityRule : AttributeCompatibilityRule<String> {
        override fun execute(details: CompatibilityCheckDetails<String>) {
            val consumerValue = details.consumerValue
            val version = details.producerValue ?: osVersion()

            if (consumerValue == null) {
                details.compatible()
            } else {
                if (version matches Regex(consumerValue)) {
                    details.compatible()
                } else {
                    details.incompatible()
                }
            }
        }
    }
}
