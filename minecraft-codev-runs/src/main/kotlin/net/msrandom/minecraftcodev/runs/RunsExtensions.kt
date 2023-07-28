package net.msrandom.minecraftcodev.runs

import groovy.lang.Closure
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.*
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.nativesConfigurationName get() = disambiguateName(MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)
val HasKotlinDependencies.nativesConfigurationName get() = disambiguateName(MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)
val KotlinTarget.nativesConfigurationName get() = disambiguateName(MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)

val KotlinTarget.downloadAssetsTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.DOWNLOAD_ASSETS_TASK)
val SourceSet.downloadAssetsTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.DOWNLOAD_ASSETS_TASK)

val KotlinTarget.extractNativesTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.EXTRACT_NATIVES_TASK)
val SourceSet.extractNativesTaskName get() = disambiguateName(MinecraftCodevRunsPlugin.EXTRACT_NATIVES_TASK)

fun KotlinDependencyHandler.natives(dependencyNotation: Any) = (this as DefaultKotlinDependencyHandler).let {
    it.project.dependencies.add(it.parent.nativesConfigurationName, dependencyNotation)
}

fun KotlinDependencyHandler.natives(dependencyNotation: String, configure: ExternalModuleDependency.() -> Unit) =
    (natives(dependencyNotation) as ExternalModuleDependency).also(configure)

fun <T : Dependency> KotlinDependencyHandler.natives(dependency: T, configure: T.() -> Unit) = (this as DefaultKotlinDependencyHandler).let {
    configure(dependency)
    it.project.dependencies.add(it.parent.nativesConfigurationName, dependency)
}

fun KotlinDependencyHandler.natives(dependencyNotation: String, configure: Closure<*>) =
    natives(dependencyNotation) { project.configure(this, configure) }

fun <T : Dependency> KotlinDependencyHandler.natives(dependency: T, configure: Closure<*>) =
    natives(dependency) { project.configure(this, configure) }
