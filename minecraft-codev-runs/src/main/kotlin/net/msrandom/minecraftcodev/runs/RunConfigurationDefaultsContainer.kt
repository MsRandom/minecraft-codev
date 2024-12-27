package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.resolve.MinecraftDownloadVariant
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.resolve.downloadMinecraftFile
import net.msrandom.minecraftcodev.core.resolve.rulesMatch
import net.msrandom.minecraftcodev.core.task.versionList
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
import kotlin.io.path.readText
import kotlin.random.Random

abstract class RunConfigurationDefaultsContainer : ExtensionAware {
    lateinit var builder: MinecraftRunConfigurationBuilder

    fun client(minecraftVersion: Provider<String>) {
        builder.action {
            val manifest = getManifest(minecraftVersion)

            val extractNativesTask =
                sourceSet.flatMap {
                    project.tasks.named(it.extractNativesTaskName, ExtractNatives::class.java)
                }

            val downloadAssetsTask =
                sourceSet.flatMap {
                    project.tasks.named(it.downloadAssetsTaskName, DownloadAssets::class.java)
                }

            beforeRun.add(extractNativesTask)
            beforeRun.add(downloadAssetsTask)

            mainClass.set(manifest.map(MinecraftVersionMetadata::mainClass))

            jvmVersion.set(manifest.map { it.javaVersion.majorVersion })

            arguments.addAll(
                manifest.flatMap {
                    val arguments =
                        it.arguments.game.ifEmpty {
                            it.minecraftArguments.split(' ').map { argument ->
                                MinecraftVersionMetadata.Argument(emptyList(), listOf(argument))
                            }
                        }

                    val fixedArguments = mutableListOf<Any?>()

                    for (argument in arguments) {
                        if (argument.rules.isEmpty()) {
                            for (value in argument.value) {
                                if (value.startsWith("\${") && value.endsWith("}")) {
                                    when (value.subSequence(2, value.length - 1)) {
                                        "version_name" -> fixedArguments.add(it.id)
                                        "assets_root" -> {
                                            fixedArguments.add(downloadAssetsTask.flatMap(DownloadAssets::assetsDirectory))
                                        }

                                        "assets_index_name" -> fixedArguments.add(it.assets)
                                        "game_assets" -> {
                                            fixedArguments.add(downloadAssetsTask.flatMap(DownloadAssets::resourcesDirectory))
                                        }

                                        "auth_access_token" -> fixedArguments.add(Random.nextLong())
                                        "user_properties" -> fixedArguments.add("{}")
                                        else -> fixedArguments.removeLastOrNull()
                                    }
                                } else {
                                    fixedArguments.add(value)
                                }
                            }
                        }
                    }

                    compileArguments(fixedArguments)
                },
            )

            jvmArguments.addAll(
                manifest.flatMap {
                    val jvmArguments =
                        it.arguments.jvm.ifEmpty {
                            // For some reason, older versions didn't include this
                            listOf(MinecraftVersionMetadata.Argument(emptyList(), listOf("-Djava.library.path=\${natives_directory}")))
                        }

                    val fixedJvmArguments = mutableListOf<Any?>()

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
                                    compileArgument(
                                        value.substring(0, templateStart),
                                        extractNativesTask.flatMap(ExtractNatives::destinationDirectory),
                                    ),
                                )

                                continue
                            }

                            if (' ' in value) {
                                fixedJvmArguments.add("\"$value\"")
                            } else {
                                fixedJvmArguments.add(value)
                            }
                        }
                    }

                    compileArguments(fixedJvmArguments)
                },
            )
        }
    }

    fun server(minecraftVersion: Provider<String>) {
        builder.action {
            val manifestProvider = getManifest(minecraftVersion)

            arguments.add("nogui")

            mainClass.set(
                manifestProvider
                    .map { manifest ->
                        val serverJar =
                            downloadMinecraftFile(
                                cacheParameters.directory.getAsPath(),
                                manifest,
                                MinecraftDownloadVariant.Server,
                                cacheParameters.getIsOffline().get(),
                            ) ?: throw UnsupportedOperationException("Version ${manifest.id} does not have a server.")

                        zipFileSystem(serverJar).use {
                            val mainPath = it.getPath("META-INF/main-class")

                            if (mainPath.exists()) {
                                mainPath.readText()
                            } else {
                                it.getPath(JarFile.MANIFEST_NAME)
                                    .inputStream()
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
                cacheParameters.versionList().version(it)
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
