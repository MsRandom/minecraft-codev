package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.osVersion
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.zipFileSystem
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.DefaultArtifactIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.os.OperatingSystem
import java.io.File
import java.time.Duration
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.random.Random

abstract class RunConfigurationDefaultsContainer @Inject constructor(val builder: MinecraftRunConfigurationBuilder) : ExtensionAware {
    private fun ruleMatches(os: MinecraftVersionMetadata.Rule.OperatingSystem): Boolean {
        if (os.name != null && OperatingSystem.forName(os.name) != OperatingSystem.current()) return false
        if (os.version != null && osVersion() matches Regex(os.version!!)) return false
        if (os.arch != null && os.arch != SystemUtils.OS_ARCH) return false

        return true
    }

    fun client(): RunConfigurationDefaultsContainer = apply {
        builder.action {
            val plugin = project.plugins.getPlugin(MinecraftCodevRunsPlugin::class.java)
            val configuration = getConfiguration()
            val artifact = configuration.findMinecraft(MinecraftComponentResolvers.CLIENT_MODULE, ::client.name)
            val manifest = getManifest(configuration, artifact)

            mainClass.set(manifest.map(MinecraftVersionMetadata::mainClass))

            jvmVersion.set(manifest.map { it.javaVersion.majorVersion })

            arguments.addAll(manifest.map {
                val arguments = it.arguments.game.ifEmpty {
                    it.minecraftArguments.split(' ').map { argument ->
                        MinecraftVersionMetadata.Argument(emptyList(), listOf(argument))
                    }
                }

                val fixedArguments = mutableListOf<MinecraftRunConfiguration.Argument>()

                for (argument in arguments) {
                    if (argument.rules.isEmpty()) {
                        for (value in argument.value) {
                            if (value.startsWith("\${") && value.endsWith("}")) {
                                when (value.subSequence(2, value.length - 1)) {
                                    "version_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(it.id))
                                    "assets_root" -> {
                                        AssetDownloader.downloadAssets(project, it.assetIndex)
                                        fixedArguments.add(MinecraftRunConfiguration.Argument(plugin.assets))
                                    }
                                    "assets_index_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(it.assets))
                                    "game_assets" -> {
                                        AssetDownloader.downloadAssets(project, it.assetIndex)
                                        fixedArguments.add(MinecraftRunConfiguration.Argument(plugin.resources))
                                    }
                                    "auth_access_token" -> fixedArguments.add(MinecraftRunConfiguration.Argument(Random.nextLong()))
                                    "user_properties" -> fixedArguments.add(MinecraftRunConfiguration.Argument("{}"))
                                    else -> fixedArguments.removeLastOrNull()
                                }
                            } else {
                                fixedArguments.add(MinecraftRunConfiguration.Argument(value))
                            }
                        }
                    }
                }

                fixedArguments
            })

            jvmArguments.addAll(manifest.map {
                val jvmArguments = it.arguments.jvm.ifEmpty {
                    // For some reason, older versions didn't include this
                    listOf(MinecraftVersionMetadata.Argument(emptyList(), listOf("-Djava.library.path=\${natives_directory}")))
                }

                val fixedJvmArguments = mutableListOf<MinecraftRunConfiguration.Argument>()

                ARGUMENTS@ for (argument in jvmArguments) {
                    var matches = argument.rules.isEmpty()

                    if (!matches) {
                        for (rule in argument.rules) {
                            if (rule.action == MinecraftVersionMetadata.RuleAction.Allow) {
                                if (rule.os == null) {
                                    continue
                                }

                                if (!ruleMatches(rule.os!!)) {
                                    continue@ARGUMENTS
                                }

                                matches = true
                            } else {
                                if (rule.os == null) {
                                    continue
                                }

                                if (ruleMatches(rule.os!!)) {
                                    continue@ARGUMENTS
                                }
                            }
                        }
                    }

                    if (matches) {
                        for (value in argument.value) {
                            if (value == "\${classpath}") {
                                fixedJvmArguments.removeLast()
                                continue
                            } else if (value.startsWith("-Dminecraft.launcher")) {
                                continue
                            } else {
                                val templateStart = value.indexOf("\${")
                                if (templateStart != -1) {
                                    val template = value.subSequence(templateStart + 2, value.indexOf('}'))
                                    if (template == "natives_directory") {
                                        @Suppress("UnstableApiUsage")
                                        val sourceSetName = StringUtils.capitalize(if (SourceSet.isMain(sourceSet.get())) "" else sourceSet.get().name)
                                        val task = project.tasks.withType(ExtractNatives::class.java).getByName("extract${sourceSetName}Natives")

                                        fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value.substring(0, templateStart), task.destinationDirectory.get().asFile.toPath()))

                                        beforeRunTasks.add(task.path)
                                    } else {
                                        continue
                                    }
                                    continue
                                } else {
                                    if (' ' in value) {
                                        fixedJvmArguments.add(MinecraftRunConfiguration.Argument("\"$value\""))
                                    } else {
                                        fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value))
                                    }
                                }
                            }
                        }
                    }
                }

                fixedJvmArguments
            })
        }
    }

    fun server(): RunConfigurationDefaultsContainer = apply {
        builder.action {
            val configurationProvider = getConfiguration()
            val artifactProvider = configurationProvider.findMinecraft(MinecraftComponentResolvers.COMMON_MODULE, ::server.name)
            val manifestProvider = getManifest(configurationProvider, artifactProvider)

            mainClass.set(
                manifestProvider
                    .zip(configurationProvider) { a, b -> a to b }
                    .zip(artifactProvider) { (manifest, configuration), artifact -> Triple(manifest, configuration, artifact) }
                    .map { (manifest, configuration, artifact) ->
                        val serverJar = project.serviceOf<RepositoriesSupplier>().get()
                            .filterIsInstance<MinecraftRepositoryImpl>()
                            .map(MinecraftRepositoryImpl::createResolver)
                            .firstNotNullOfOrNull { repository ->
                                MinecraftArtifactResolver.resolveMojangFile(
                                    manifest,
                                    MinecraftCodevPlugin.getCacheProvider(project.gradle).manager("minecraft"),
                                    project.serviceOf(),
                                    repository,
                                    MinecraftComponentResolvers.SERVER_DOWNLOAD
                                ) { file: File, duration: Duration ->
                                    val cachePolicy = (configuration.resolutionStrategy as ResolutionStrategyInternal).cachePolicy

                                    project.serviceOf<StartParameterResolutionOverride>().applyToCachePolicy(cachePolicy)

                                    cachePolicy.artifactExpiry(
                                        DefaultArtifactIdentifier(artifact.moduleVersion.id, artifact.name, artifact.type, artifact.extension, artifact.classifier),
                                        file,
                                        duration,
                                        false,
                                        true
                                    ).isMustCheck
                                }
                            } ?: throw UnsupportedOperationException("Version $artifactProvider does not have a server.")

                        zipFileSystem(serverJar.toPath()).use {
                            val mainPath = it.getPath("META-INF/main-class")
                            if (mainPath.exists()) {
                                String(mainPath.readBytes())
                            } else {
                                it.getPath(JarFile.MANIFEST_NAME).inputStream().use(::Manifest).mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
                            }
                        }
                    }
            )

            jvmVersion.set(manifestProvider.map { it.javaVersion.majorVersion })
        }
    }

    companion object {
        private const val WRONG_SIDE_ERROR = "There is no Minecraft %s dependency for defaults.%s to work"

        internal fun MinecraftRunConfiguration.getConfiguration() = sourceSet.flatMap { project.configurations.named(it.runtimeClasspathConfigurationName) }

        internal fun Provider<Configuration>.findMinecraft(type: String, caller: String) = map {
            it.resolvedConfiguration.resolvedArtifacts.firstOrNull { artifact ->
                artifact.moduleVersion.id.group == MinecraftComponentResolvers.GROUP && artifact.moduleVersion.id.name == type
            } ?: throw UnsupportedOperationException(WRONG_SIDE_ERROR.format(type, caller))
        }

        internal fun MinecraftRunConfiguration.getManifest(configurationProvider: Provider<Configuration>, artifactProvider: Provider<ResolvedArtifact>) = artifactProvider
            .zip(configurationProvider) { artifact, configuration -> artifact to configuration }
            .map { (artifact, configuration) ->
                val repositories = project.serviceOf<RepositoriesSupplier>().get()
                    .filterIsInstance<MinecraftRepositoryImpl>()
                    .map(MinecraftRepositoryImpl::createResolver)

                repositories.firstNotNullOfOrNull { repository ->
                    val cachePolicy = (configuration.resolutionStrategy as ResolutionStrategyInternal).cachePolicy

                    project.serviceOf<StartParameterResolutionOverride>().applyToCachePolicy(cachePolicy)

                    MinecraftMetadataGenerator.getVersionManifest(
                        artifact.id.componentIdentifier as ModuleComponentIdentifier,
                        repository.url,
                        MinecraftCodevPlugin.getCacheProvider(project.gradle).manager("minecraft"),
                        cachePolicy,
                        repository.transport.resourceAccessor,
                        project.serviceOf(),
                        null
                    )
                } ?: throw UnsupportedOperationException("Game resolved with version ${artifact.moduleVersion.id.version} but no metadata found for said version, this should not be possible.")
            }
    }
}
