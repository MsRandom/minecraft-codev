package net.msrandom.minecraftcodev.fabric.runs

import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.toPath
import net.msrandom.minecraftcodev.fabric.FabricInstaller
import net.msrandom.minecraftcodev.fabric.loadFabricInstaller
import net.msrandom.minecraftcodev.runs.*
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.provider.Provider
import java.io.File
import kotlin.io.path.createDirectories

open class FabricRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    private fun defaults(sidedMain: FabricInstaller.MainClass.() -> String) {
        defaults.builder.jvmArguments("-Dfabric.development=true")
        // defaults.builder.jvmArguments("-Dmixin.env.remapRefMap=true")

        defaults.builder.action {
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

        defaults.builder.jvmArguments()
    }

    fun client(version: Provider<String>) {
        defaults(FabricInstaller.MainClass::client)

        defaults.builder.action {
            val assetIndex =
                version.map {
                    cacheParameters
                        .versionList()
                        .version(it)
                        .assetIndex
                }

            val extractNativesTask = project.tasks.withType(ExtractNatives::class.java).getByName(sourceSet.get().extractNativesTaskName)

            val downloadAssetsTask =
                sourceSet.flatMap {
                    project.tasks.named(it.downloadAssetsTaskName, DownloadAssets::class.java)
                }

            val nativesDirectory = extractNativesTask.destinationDirectory
            val assetsDirectory = downloadAssetsTask.flatMap(DownloadAssets::assetsDirectory)

            arguments.add(compileArgument("--assetsDir=", assetsDirectory))
            arguments.add(compileArgument("--assetIndex=", assetIndex.map(MinecraftVersionMetadata.AssetIndex::id)))

            jvmArguments.add(compileArgument("-Djava.library.path=", nativesDirectory))
            jvmArguments.add(compileArgument("-Dorg.lwjgl.librarypath=", nativesDirectory))

            beforeRun.add(extractNativesTask)
            beforeRun.add(downloadAssetsTask)
        }
    }

    fun server() {
        defaults(FabricInstaller.MainClass::server)

        defaults.builder.apply {
            arguments("nogui")
        }
    }

    fun data(
        version: Provider<String>,
        action: Action<FabricDatagenRunConfigurationData>,
    ) {
        client(version)

        defaults.builder.action {
            val data = project.objects.newInstance(FabricDatagenRunConfigurationData::class.java)

            action.execute(data)

            jvmArguments.add("-Dfabric-api.datagen")
            jvmArguments.add(compileArgument("-Dfabric-api.datagen.output-dir=", data.getOutputDirectory(this)))
            jvmArguments.add(compileArgument("-Dfabric-api.datagen.modid=", data.modId))
        }
    }

    private fun gameTest() {
        defaults.builder.apply {
            jvmArguments(
                "-Dfabric-api.gametest",
                "-Dfabric.autoTest",
            )
        }
    }

    fun gameTestServer() {
        server()

        gameTest()
    }

    fun gameTestClient(version: Provider<String>) {
        client(version)

        gameTest()
    }

    fun gameTestData(
        version: Provider<String>,
        action: Action<FabricDatagenRunConfigurationData>,
    ) {
        data(version, action)

        gameTest()
    }
}

abstract class FabricDatagenRunConfigurationData : DatagenRunConfigurationData
