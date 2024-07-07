package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetElements
import net.msrandom.minecraftcodev.core.utils.extension
import net.msrandom.minecraftcodev.core.utils.getCacheDirectory
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Plugin
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import java.nio.file.Path

class MinecraftCodevRunsPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            // Log4j configs
            val cache = getCacheDirectory(this)
            val logging: Path = cache.resolve("logging")

            fun addNativesConfiguration(nativesConfigurationName: String) =
                configurations.maybeCreate(nativesConfigurationName).apply {
                    isCanBeConsumed = false
                }

            fun addSourceElements(
                nativesConfigurationName: String,
                extractNativesTaskName: String,
                downloadAssetsTaskName: String,
            ) {
                val configuration = addNativesConfiguration(nativesConfigurationName)

                tasks.register(extractNativesTaskName, ExtractNatives::class.java) {
                    it.natives.set(configuration)
                }

                tasks.register(downloadAssetsTaskName, DownloadAssets::class.java) { task ->
                    val fileName = if (name.isEmpty()) "${SourceSet.MAIN_SOURCE_SET_NAME}.json" else "$name.json"

                    task.assetIndexFile.set(layout.buildDirectory.dir("assetIndices").map { it.file(fileName) })
                }
            }

            createSourceSetElements {
                addSourceElements(it.nativesConfigurationName, it.extractNativesTaskName, it.downloadAssetsTaskName)
            }

            val runs =
                extension<MinecraftCodevExtension>()
                    .extensions
                    .create(RunsContainer::class.java, "runs", RunsContainerImpl::class.java, cache)

            runs.extensions.create("defaults", RunConfigurationDefaultsContainer::class.java)

            project.integrateIdeaRuns()

            runs.all { builder ->
                val name = "${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(builder.name)}"
                tasks.register(name, JavaExec::class.java) { javaExec ->
                    val configuration = builder.build()

                    javaExec.doFirst { task ->
                        (task as JavaExec).environment = configuration.environment.get().mapValues { it.value.compile() }
                    }

                    // TODO Set java version

                    javaExec.argumentProviders.add(
                        configuration.arguments.map { arguments -> arguments.map(MinecraftRunConfiguration.Argument::compile) }::get,
                    )
                    javaExec.jvmArgumentProviders.add(
                        configuration.jvmArguments.map { arguments ->
                            arguments.map(MinecraftRunConfiguration.Argument::compile)
                        }::get,
                    )
                    javaExec.workingDir(configuration.executableDirectory)
                    javaExec.mainClass.set(configuration.mainClass)

                    javaExec.classpath = configuration.sourceSet.get().runtimeClasspath

                    javaExec.group = ApplicationPlugin.APPLICATION_GROUP

                    javaExec.dependsOn(configuration.beforeRun)

                    javaExec.dependsOn(
                        configuration.dependsOn.map {
                            it.map { other -> "${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(other.name)}" }
                        },
                    )

                    javaExec.dependsOn(configuration.sourceSet.map(SourceSet::getClassesTaskName))
                }
            }
        }

    companion object {
        const val NATIVES_CONFIGURATION = "natives"
        const val EXTRACT_NATIVES_TASK = "extractNatives"
        const val DOWNLOAD_ASSETS_TASK = "downloadAssets"
    }
}
