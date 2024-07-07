package net.msrandom.minecraftcodev.fabric.runs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.runs.*
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.file.Directory
import java.io.File
import kotlin.io.path.createDirectories

open class FabricRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    private fun defaults() {
        defaults.builder.jvmArguments("-Dfabric.development=true")
        // defaults.builder.jvmArguments("-Dmixin.env.remapRefMap=true")

        defaults.builder.action {
            val file = project.layout.buildDirectory.dir("fabricRemapClasspath").get().file("classpath.txt")

            val runtimeClasspath = sourceSet.get().runtimeClasspath

            jvmArguments.add(
                project.provider {
                    file.asFile.toPath().parent.createDirectories()
                    file.asFile.writeText(runtimeClasspath.files.joinToString("\n", transform = File::getAbsolutePath))

                    MinecraftRunConfiguration.Argument("-Dfabric.remapClasspathFile=", file.asFile)
                },
            )
        }
        defaults.builder.jvmArguments()
    }

    fun client() {
        defaults()

        defaults.builder.mainClass(KNOT_CLIENT)

        defaults.builder.action {
            val extractNativesTask =
                sourceSet.flatMap {
                    project.tasks.withType(ExtractNatives::class.java)
                        .named(it.extractNativesTaskName)
                }

            val codev = project.extension<MinecraftCodevExtension>().extension<RunsContainer>()

            val downloadAssetsTask =
                project.tasks.withType(DownloadAssets::class.java)
                    .named(sourceSet.get().downloadAssetsTaskName)

            val nativesDirectory = extractNativesTask.flatMap(ExtractNatives::destinationDirectory).map(Directory::getAsFile)

            val assetIndex =
                downloadAssetsTask.map {
                    it.assetIndexFile.asFile.get().inputStream().use {
                        Json.decodeFromStream<MinecraftVersionMetadata.AssetIndex>(it)
                    }.id
                }

            arguments.add(MinecraftRunConfiguration.Argument("--assetsDir=", codev.assetsDirectory.asFile))
            arguments.add(MinecraftRunConfiguration.Argument("--assetIndex=", assetIndex))

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Djava.library.path=", nativesDirectory))
            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dorg.lwjgl.librarypath=", nativesDirectory))

            beforeRun.add(extractNativesTask)
            beforeRun.add(downloadAssetsTask)
        }
    }

    fun server() {
        defaults()

        defaults.builder.apply {
            arguments("nogui")
            mainClass(KNOT_SERVER)
        }
    }

    fun data(action: Action<FabricDatagenRunConfigurationData>) {
        client()

        defaults.builder.action {
            val data = project.objects.newInstance(FabricDatagenRunConfigurationData::class.java)

            action.execute(data)

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dfabric-api.datagen"))
            jvmArguments.add(
                data.getOutputDirectory(this).map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.output-dir=", it) },
            )
            jvmArguments.add(data.modId.map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.modid=", it) })
        }
    }

    private fun gameTest(client: Boolean) {
        if (client) {
            client()
        } else {
            server()
        }

        defaults.builder.apply {
            jvmArguments(
                "-Dfabric-api.gametest",
                "-Dfabric.autoTest",
            )
        }
    }

    fun gameTestServer() = gameTest(false)

    fun gameTestClient() = gameTest(true)

    private companion object {
        private const val KNOT_SERVER = "net.fabricmc.loader.launch.knot.KnotServer"
        private const val KNOT_CLIENT = "net.fabricmc.loader.launch.knot.KnotClient"
    }
}

abstract class FabricDatagenRunConfigurationData : DatagenRunConfigurationData
