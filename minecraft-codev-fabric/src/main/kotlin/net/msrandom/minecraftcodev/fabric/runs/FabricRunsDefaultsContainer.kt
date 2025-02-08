package net.msrandom.minecraftcodev.fabric.runs

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.task.versionList
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.fabric.FabricInstaller
import net.msrandom.minecraftcodev.fabric.loadFabricInstaller
import net.msrandom.minecraftcodev.runs.DatagenRunConfigurationData
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import java.io.File
import kotlin.io.path.createDirectories

open class FabricRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    private fun defaults(sidedMain: FabricInstaller.MainClass.() -> String) {
        defaults.configuration.jvmArguments("-Dfabric.development=true")
        defaults.configuration.jvmArguments("-Dmixin.env.remapRefMap=true")

        defaults.configuration.apply {
            val remapClasspathDirectory = project.layout.buildDirectory.dir("fabricRemapClasspath")

            mainClass.set(
                sourceSet.map {
                    val fabricInstaller = loadFabricInstaller(it.runtimeClasspath, false)!!

                    fabricInstaller.mainClass.sidedMain()
                },
            )

            jvmArguments.add(
                sourceSet.zip(remapClasspathDirectory, ::Pair).flatMap { (sourceSet, directory) ->
                    val file = directory.file("classpath.txt")
                    val runtimeClasspath = sourceSet.runtimeClasspath

                    file.toPath().parent.createDirectories()
                    file.asFile.writeText(runtimeClasspath.files.joinToString("\n", transform = File::getAbsolutePath))

                    compileArgument("-Dfabric.remapClasspathFile=", file.asFile)
                },
            )
        }
    }

    fun client(action: Action<FabricRunConfigurationData>) {
        val data = defaults.configuration.project.objects.newInstance(FabricRunConfigurationData::class.java)

        action.execute(data)

        client(data)
    }

    private fun addAssets(data: FabricRunConfigurationData) {
        defaults.configuration.apply {
            val assetIndex =
                data.minecraftVersion.map {
                    cacheParameters
                        .versionList()
                        .version(it)
                        .assetIndex
                }

            val assetsDirectory = data.downloadAssetsTask.flatMap(DownloadAssets::assetsDirectory)

            arguments.add(compileArgument("--assetsDir=", assetsDirectory))
            arguments.add(compileArgument("--assetIndex=", assetIndex.map(MinecraftVersionMetadata.AssetIndex::id)))

            beforeRun.add(data.downloadAssetsTask)
        }
    }

    private fun client(data: FabricRunConfigurationData) {
        defaults(FabricInstaller.MainClass::client)
        addAssets(data)

        defaults.configuration.apply {
            val nativesDirectory = data.extractNativesTask.flatMap(ExtractNatives::destinationDirectory)

            jvmArguments.add(compileArgument("-Djava.library.path=", nativesDirectory))
            jvmArguments.add(compileArgument("-Dorg.lwjgl.librarypath=", nativesDirectory))

            beforeRun.add(data.extractNativesTask)
        }
    }

    fun server() {
        defaults(FabricInstaller.MainClass::server)

        defaults.configuration.arguments("nogui")
    }

    fun data(action: Action<FabricDatagenRunConfigurationData>) {
        defaults(FabricInstaller.MainClass::server)

        val data = defaults.configuration.project.objects.newInstance(FabricDatagenRunConfigurationData::class.java)

        action.execute(data)

        data(data)
    }

    fun clientData(action: Action<FabricDatagenRunConfigurationData>) {
        // Fabric doesn't have a dedicated client data entrypoint or properties
        data(action)
    }

    private fun data(data: FabricDatagenRunConfigurationData) {
        addAssets(data)

        defaults.configuration.apply {
            jvmArguments.add("-Dfabric-api.datagen")
            jvmArguments.add(compileArgument("-Dfabric-api.datagen.output-dir=", data.outputDirectory))
            jvmArguments.add(compileArgument("-Dfabric-api.datagen.modid=", data.modId))
        }
    }

    private fun gameTest() {
        defaults.configuration.jvmArguments(
            "-Dfabric-api.gametest",
            "-Dfabric.autoTest",
        )
    }

    fun gameTestServer() {
        server()

        gameTest()
    }

    fun gameTestClient(action: Action<FabricRunConfigurationData>) {
        client(action)

        gameTest()
    }
}

interface FabricRunConfigurationData {
    val minecraftVersion: Property<String>
        @Input
        get

    val extractNativesTask: Property<ExtractNatives>
        @Input
        get

    val downloadAssetsTask: Property<DownloadAssets>
        @Input
        get
}

abstract class FabricDatagenRunConfigurationData : DatagenRunConfigurationData, FabricRunConfigurationData
