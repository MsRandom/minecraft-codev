package net.msrandom.minecraftcodev.fabric.runs

import kotlinx.coroutines.runBlocking
import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.fabric.FabricInstaller
import net.msrandom.minecraftcodev.fabric.loadFabricInstaller
import net.msrandom.minecraftcodev.runs.*
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.gradle.api.Action
import org.gradle.api.file.Directory
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
                sourceSet.zip(remapClasspathDirectory, ::Pair).map { (sourceSet, directory) ->
                    val file = directory.file("classpath.txt")
                    val runtimeClasspath = sourceSet.runtimeClasspath

                    file.asFile.toPath().parent.createDirectories()
                    file.asFile.writeText(runtimeClasspath.files.joinToString("\n", transform = File::getAbsolutePath))

                    MinecraftRunConfiguration.Argument("-Dfabric.remapClasspathFile=", file.asFile)
                },
            )
        }

        defaults.builder.jvmArguments()
    }

    fun client(version: Provider<String>) {
        defaults(FabricInstaller.MainClass::client)

        defaults.builder.action {
            val codev = project.extension<MinecraftCodevExtension>()
            val runs = codev.extension<RunsContainer>()

            val assetIndex =
                version.map {
                    runBlocking {
                        codev
                            .getVersionList()
                            .version(it)
                            .assetIndex
                    }
                }

            val extractNativesTask =
                sourceSet.flatMap {
                    project.tasks.withType(ExtractNatives::class.java)
                        .named(it.extractNativesTaskName)
                }

            val downloadAssetsTask =
                sourceSet.flatMap {
                    project.tasks.withType(DownloadAssets::class.java).named(it.downloadAssetsTaskName) {
                        it.useAssetIndex(assetIndex.get())
                    }
                }

            val nativesDirectory = extractNativesTask.flatMap(ExtractNatives::destinationDirectory).map(Directory::getAsFile)

            arguments.add(MinecraftRunConfiguration.Argument("--assetsDir=", runs.assetsDirectory.asFile))
            arguments.add(MinecraftRunConfiguration.Argument("--assetIndex=", assetIndex.map(MinecraftVersionMetadata.AssetIndex::id)))

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Djava.library.path=", nativesDirectory))
            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dorg.lwjgl.librarypath=", nativesDirectory))

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

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dfabric-api.datagen"))
            jvmArguments.add(
                data.getOutputDirectory(this).map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.output-dir=", it) },
            )
            jvmArguments.add(data.modId.map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.modid=", it) })
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
