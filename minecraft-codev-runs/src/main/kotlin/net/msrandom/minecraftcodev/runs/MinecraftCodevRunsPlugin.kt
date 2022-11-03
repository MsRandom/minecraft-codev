package net.msrandom.minecraftcodev.runs

import net.msrandom.minecraftcodev.core.MinecraftCodevExtension
import net.msrandom.minecraftcodev.core.applyPlugin
import net.msrandom.minecraftcodev.core.caches.CodevCacheManager
import net.msrandom.minecraftcodev.core.createSourceSetElements
import net.msrandom.minecraftcodev.core.extendKotlinConfigurations
import net.msrandom.minecraftcodev.runs.task.ExtractNatives
import net.msrandom.minecraftcodev.runs.task.GenerateIdeaRuns
import org.apache.commons.lang3.StringUtils
import org.gradle.api.Plugin
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.plugins.PluginAware
import org.gradle.api.tasks.JavaExec
import org.gradle.initialization.layout.GlobalCacheDir
import java.nio.file.Path
import javax.inject.Inject

class MinecraftCodevRunsPlugin<T : PluginAware> @Inject constructor(cacheDir: GlobalCacheDir) : Plugin<T> {
    private val cache: Path = cacheDir.dir.toPath().resolve(CodevCacheManager.ROOT_NAME)

    // Asset indexes and objects
    val assets: Path = cache.resolve("assets")

    // Legacy assets
    val resources: Path = cache.resolve("resources")

    // Log4j configs
    val logging: Path = cache.resolve("logging")

    override fun apply(target: T) = applyPlugin(target) {
        val capitalizedNatives = StringUtils.capitalize(NATIVES_CONFIGURATION)

        createSourceSetElements { name, isSourceSet ->
            val configurationName = if (name.isEmpty()) NATIVES_CONFIGURATION else name + capitalizedNatives

            val configuration = configurations.maybeCreate(configurationName).apply {
                isCanBeConsumed = false
            }

            if (isSourceSet) {
                tasks.register("extract${StringUtils.capitalize(name)}$capitalizedNatives", ExtractNatives::class.java) {
                    it.natives.set(configuration)
                }
            }
        }

        extendKotlinConfigurations(NATIVES_CONFIGURATION)

        val runs = extensions
            .getByType(MinecraftCodevExtension::class.java)
            .extensions
            .create(RunsContainer::class.java, "runs", RunsContainerImpl::class.java)

        runs.extensions.create("defaults", RunConfigurationDefaultsContainer::class.java)

        tasks.register("generateIdeaRuns", GenerateIdeaRuns::class.java)

        runs.all { builder ->
            tasks.register("${ApplicationPlugin.TASK_RUN_NAME}${StringUtils.capitalize(builder.name)}", JavaExec::class.java) { javaExec ->
                val configuration = builder.build(project)

                javaExec.args(configuration.arguments.get().map { it.parts.joinToString("") })
                javaExec.jvmArgs(configuration.jvmArguments.get().map { it.parts.joinToString("") })
                javaExec.environment(configuration.environment.get().mapValues { it.value.parts.joinToString("") })
                javaExec.classpath = configuration.sourceSet.get().runtimeClasspath
                javaExec.workingDir(configuration.workingDirectory)
                javaExec.mainClass.set(configuration.mainClass)
                javaExec.dependsOn(*configuration.beforeRunTasks.get().toTypedArray())

                javaExec.group = ApplicationPlugin.APPLICATION_GROUP
            }
        }
    }

    companion object {
        const val NATIVES_CONFIGURATION = "natives"
    }
}
