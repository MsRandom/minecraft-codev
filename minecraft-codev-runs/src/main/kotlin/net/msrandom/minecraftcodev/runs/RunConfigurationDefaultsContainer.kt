package net.msrandom.minecraftcodev.runs

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.resolve.rulesMatch
import net.msrandom.minecraftcodev.core.utils.getAsPath
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readBytes
import kotlin.random.Random

abstract class RunConfigurationDefaultsContainer : ExtensionAware {
    lateinit var builder: MinecraftRunConfigurationBuilder

    fun client(minecraftVersion: Provider<String>) {
        builder.action {
            val manifest = getManifest(minecraftVersion)

            val extractNativesTask =
                sourceSet.flatMap {
                    project.tasks
                        .withType(ExtractNatives::class.java)
                        .named(it.extractNativesTaskName)
                }

            val downloadAssetsTask =
                sourceSet.flatMap {
                    project.tasks
                        .withType(DownloadAssets::class.java)
                        .named(it.downloadAssetsTaskName)
                }

            beforeRun.add(extractNativesTask)
            beforeRun.add(downloadAssetsTask)

            mainClass.set(manifest.map(MinecraftVersionMetadata::mainClass))

            jvmVersion.set(manifest.map { it.javaVersion.majorVersion })

            arguments.addAll(
                manifest.map {
                    val arguments =
                        it.arguments.game.ifEmpty {
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
                                            fixedArguments.add(
                                                MinecraftRunConfiguration.Argument(
                                                    downloadAssetsTask.flatMap(DownloadAssets::assetsDirectory),
                                                ),
                                            )
                                        }

                                        "assets_index_name" -> fixedArguments.add(MinecraftRunConfiguration.Argument(it.assets))
                                        "game_assets" -> {
                                            fixedArguments.add(
                                                MinecraftRunConfiguration.Argument(
                                                    downloadAssetsTask.flatMap(DownloadAssets::resourcesDirectory),
                                                ),
                                            )
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
                },
            )

            jvmArguments.addAll(
                manifest.map {
                    val jvmArguments =
                        it.arguments.jvm.ifEmpty {
                            // For some reason, older versions didn't include this
                            listOf(MinecraftVersionMetadata.Argument(emptyList(), listOf("-Djava.library.path=\${natives_directory}")))
                        }

                    val fixedJvmArguments = mutableListOf<MinecraftRunConfiguration.Argument>()

                    ARGUMENTS@ for (argument in jvmArguments) {
                        if (!rulesMatch(argument.rules)) continue

                        for (value in argument.value) {
                            if (value == "\${classpath}") {
                                fixedJvmArguments.removeLast()
                                continue
                            }

                            if (value.startsWith("-Dminecraft.launcher")) {
                                continue
                            }

                            val templateStart = value.indexOf("\${")

                            if (templateStart != -1) {
                                val template = value.subSequence(templateStart + 2, value.indexOf('}'))

                                if (template != "natives_directory") {
                                    continue
                                }

                                fixedJvmArguments.add(
                                    MinecraftRunConfiguration.Argument(
                                        value.substring(0, templateStart),
                                        extractNativesTask.flatMap(ExtractNatives::destinationDirectory),
                                    ),
                                )

                                continue
                            }

                            if (' ' in value) {
                                fixedJvmArguments.add(MinecraftRunConfiguration.Argument("\"$value\""))
                            } else {
                                fixedJvmArguments.add(MinecraftRunConfiguration.Argument(value))
                            }
                        }
                    }

                    fixedJvmArguments
                },
            )
        }
    }

    fun server(minecraftVersion: Provider<String>) {
        builder.action {
            val manifestProvider = getManifest(minecraftVersion)

            arguments.add(MinecraftRunConfiguration.Argument("nogui"))

            mainClass.set(
                manifestProvider
                    .map { manifest ->
                        val serverJar =
                            runBlocking {
                                downloadMinecraftFile(
                                    cacheParameters.directory.getAsPath(),
                                    manifest,
                                    MinecraftDownloadVariant.Server,
                                    cacheParameters.isOffline.get(),
                                ) ?: throw UnsupportedOperationException("Version ${manifest.id} does not have a server.")
                            }

                        zipFileSystem(serverJar).use {
                            val mainPath = it.base.getPath("META-INF/main-class")
                            if (mainPath.exists()) {
                                String(mainPath.readBytes())
                            } else {
                                it.base
                                    .getPath(
                                        JarFile.MANIFEST_NAME,
                                    ).inputStream()
                                    .use(::Manifest)
                                    .mainAttributes
                                    .getValue(Attributes.Name.MAIN_CLASS)
                            }
                        }
                    },
            )

            jvmVersion.set(manifestProvider.map { it.javaVersion.majorVersion })
        }
    }

    companion object {
        fun MinecraftRunConfiguration.getManifest(minecraftVersion: Provider<String>): Provider<MinecraftVersionMetadata> =
            minecraftVersion.map {
                runBlocking {
                    cacheParameters.versionList().version(it)
                }
            }
    }
}

interface DatagenRunConfigurationData {
    val modId: Property<String>
        @Input
        get

    val outputDirectory: DirectoryProperty
        @InputDirectory
        @Optional
        get

    fun getOutputDirectory(runConfiguration: MinecraftRunConfiguration): Provider<Directory> {
        val moduleName = runConfiguration.sourceSet.map(SourceSet::getName)

        return outputDirectory.orElse(
            runConfiguration.project.layout.buildDirectory
                .dir("generatedResources")
                .flatMap { moduleName.map(it::dir) },
        )
    }
}
