package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.repository.MinecraftRepositoryImpl
import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers
import net.msrandom.minecraftcodev.core.resolve.MinecraftMetadataGenerator
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.getCacheProvider
import net.msrandom.minecraftcodev.core.utils.osVersion
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.artifacts.RepositoriesSupplier
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.StartParameterResolutionOverride
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.os.OperatingSystem
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.random.Random

abstract class RunConfigurationDefaultsContainer : ExtensionAware {
    lateinit var builder: MinecraftRunConfigurationBuilder

    private fun ruleMatches(os: MinecraftVersionMetadata.Rule.OperatingSystem): Boolean {
        if (os.name != null && OperatingSystem.forName(os.name) != OperatingSystem.current()) return false
        if (os.version != null && osVersion() matches Regex(os.version!!)) return false
        if (os.arch != null && os.arch != SystemUtils.OS_ARCH) return false

        return true
    }

    fun client(): RunConfigurationDefaultsContainer = apply {
        builder.action {
            val configuration = getConfiguration()
            val artifact = configuration.findMinecraft(MinecraftComponentResolvers.CLIENT_MODULE, ::client.name)
            val manifest = getManifest(configuration, artifact)

            @Suppress("UnstableApiUsage")
            val sourceSetName = sourceSet.get().takeUnless(SourceSet::isMain)?.name?.let(StringUtils::capitalize).orEmpty()
            val extractNativesTask = project.tasks.withType(ExtractNatives::class.java).named("extract${sourceSetName}${StringUtils.capitalize(MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)}")
            val downloadAssetsTask = project.tasks.withType(DownloadAssets::class.java).named("download${sourceSetName}Assets")

            beforeRun.add(extractNativesTask)
            beforeRun.add(downloadAssetsTask)

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
                                val runs = project.extensions.getByType(MinecraftCodevExtension::class.java).extensions.getByType(RunsContainer::class.java)

                                when (value.subSequence(2, value.length - 1)) {
                                    "version_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(it.id))
                                    "assets_root" -> {
                                        downloadAssetsTask.configure { task ->
                                            task.assetIndex.convention(it.assetIndex)
                                        }

                                        fixedArguments.add(MinecraftRunConfiguration.Argument(runs.assetsDirectory.asFile.get()))
                                    }

                                    "assets_index_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(it.assets))
                                    "game_assets" -> {
                                        downloadAssetsTask.configure { task ->
                                            task.assetIndex.convention(it.assetIndex)
                                        }

                                        fixedArguments.add(MinecraftRunConfiguration.Argument(runs.resourcesDirectory.asFile.get()))
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
                                        fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value.substring(0, templateStart), extractNativesTask.flatMap(ExtractNatives::destinationDirectory)))
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
                    .map { manifest ->
                        val serverJar = project.serviceOf<RepositoriesSupplier>().get()
                            .filterIsInstance<MinecraftRepositoryImpl>()
                            .map(MinecraftRepositoryImpl::createResolver)
                            .firstNotNullOfOrNull { repository ->
                                MinecraftArtifactResolver.resolveMojangFile(
                                    manifest,
                                    getCacheProvider(project.gradle).manager("minecraft"),
                                    project.serviceOf(),
                                    repository,
                                    MinecraftComponentResolvers.SERVER_DOWNLOAD
                                )
                            } ?: throw UnsupportedOperationException("Version $artifactProvider does not have a server.")

                        zipFileSystem(serverJar.toPath()).use {
                            val mainPath = it.base.getPath("META-INF/main-class")
                            if (mainPath.exists()) {
                                String(mainPath.readBytes())
                            } else {
                                it.base.getPath(JarFile.MANIFEST_NAME).inputStream().use(::Manifest).mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
                            }
                        }
                    }
            )

            jvmVersion.set(manifestProvider.map { it.javaVersion.majorVersion })
        }
    }

    companion object {
        private const val WRONG_SIDE_ERROR = "There is no Minecraft %s dependency for defaults.%s to work"

        fun MinecraftRunConfiguration.getConfiguration() = sourceSet.flatMap { project.configurations.named(it.runtimeClasspathConfigurationName) }

        fun Provider<Configuration>.findMinecraft(type: String, caller: String) = map {
            it.resolvedConfiguration.resolvedArtifacts.firstOrNull { artifact ->
                artifact.moduleVersion.id.group == MinecraftComponentResolvers.GROUP && artifact.moduleVersion.id.name == type
            } ?: throw UnsupportedOperationException(WRONG_SIDE_ERROR.format(type, caller))
        }

        fun MinecraftRunConfiguration.getManifest(configurationProvider: Provider<Configuration>, artifactProvider: Provider<ResolvedArtifact>) = artifactProvider
            .zip(configurationProvider) { artifact, configuration -> artifact to configuration }
            .map { (artifact, configuration) ->
                val repositories = project.serviceOf<RepositoriesSupplier>().get()
                    .filterIsInstance<MinecraftRepositoryImpl>()
                    .map(MinecraftRepositoryImpl::createResolver)

                repositories.firstNotNullOfOrNull { repository ->
                    val cachePolicy = (configuration.resolutionStrategy as ResolutionStrategyInternal).cachePolicy
                    val id = artifact.id.componentIdentifier as ModuleComponentIdentifier

                    project.serviceOf<StartParameterResolutionOverride>().applyToCachePolicy(cachePolicy)

                    MinecraftMetadataGenerator.getVersionManifest(
                        DefaultModuleComponentIdentifier(id.moduleIdentifier, artifact.moduleVersion.id.version),
                        repository.url,
                        getCacheProvider(project.gradle).manager("minecraft"),
                        cachePolicy,
                        repository.resourceAccessor,
                        project.serviceOf(),
                        project.serviceOf(),
                        null
                    )
                } ?: throw UnsupportedOperationException("Game resolved with version ${artifact.moduleVersion.id.version} but no metadata found for said version, this should not be possible.")
            }
    }
}
