package net.msrandom.minecraftcodev.runs

import groovy.lang.Closure
import net.msrandom.minecraftcodev.core.utils.sourceSetName
import org.gradle.api.Named
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.nativesConfigurationName get() = sourceSetName(name, MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)
val HasKotlinDependencies.nativesConfigurationName get() = sourceSetName((this as Named).name, MinecraftCodevRunsPlugin.NATIVES_CONFIGURATION)

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
