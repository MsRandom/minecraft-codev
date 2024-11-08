package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.utils.applyPlugin
import net.msrandom.minecraftcodev.core.utils.createSourceSetElements
import net.msrandom.minecraftcodev.core.utils.getCacheDirectoryProvider
import net.msrandom.minecraftcodev.runs.task.DownloadAssets
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Plugin
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

class MinecraftCodevRunsPlugin<T : PluginAware> : Plugin<T> {
    override fun apply(target: T) =
        applyPlugin(target) {
            // Log4j configs
            val cache = getCacheDirectoryProvider(this)
            // val logging: Path = cache.resolve("logging")

            fun addSourceElements(
                extractNativesTaskName: String,
                downloadAssetsTaskName: String,
            ) {
                tasks.register(extractNativesTaskName, ExtractNatives::class.java)
                tasks.register(downloadAssetsTaskName, DownloadAssets::class.java)
            }

            createSourceSetElements {
                addSourceElements(
                    it.extractNativesTaskName,
                    it.downloadAssetsTaskName,
                )
            }

            val runs = project.extensions.create(RunsContainer::class.java, "minecraftRuns", RunsContainerImpl::class.java, cache)

            runs.extensions.create("defaults", RunConfigurationDefaultsContainer::class.java)

            project.integrateIdeaRuns()

            runs.all { builder ->
                val name = "${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(builder.name)}"

                tasks.register(name, JavaExec::class.java) { javaExec ->
                    val configuration = builder.build()

                    javaExec.environment = configuration.environment.get().mapValues {
                        object {
                            override fun toString() = it.value.compile()
                        }
                    }

                    javaExec.javaLauncher.set(project.serviceOf<JavaToolchainService>().launcherFor {
                        it.languageVersion.set(configuration.jvmVersion.map(JavaLanguageVersion::of))
                    })

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
}
