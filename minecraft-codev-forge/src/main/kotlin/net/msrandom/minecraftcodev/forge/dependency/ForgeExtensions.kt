package net.msrandom.minecraftcodev.forge.dependency

import groovy.lang.Closure
import net.msrandom.minecraftcodev.core.utils.sourceSetName
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import org.gradle.api.Named
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.patchesConfigurationName get() = sourceSetName(name, MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)
val HasKotlinDependencies.patchesConfigurationName get() = sourceSetName(sourceSetName, MinecraftCodevForgePlugin.PATCHES_CONFIGURATION)

fun KotlinDependencyHandler.patches(dependencyNotation: Any) = (this as DefaultKotlinDependencyHandler).let {
    it.project.dependencies.add(it.parent.patchesConfigurationName, dependencyNotation)
}

fun KotlinDependencyHandler.patches(dependencyNotation: String, configure: ExternalModuleDependency.() -> Unit) =
    (patches(dependencyNotation) as ExternalModuleDependency).also(configure)

fun <T : Dependency> KotlinDependencyHandler.patches(dependency: T, configure: T.() -> Unit) = (this as DefaultKotlinDependencyHandler).let {
    configure(dependency)
    it.project.dependencies.add(it.parent.patchesConfigurationName, dependency)
}

fun KotlinDependencyHandler.patches(dependencyNotation: String, configure: Closure<*>) =
    patches(dependencyNotation) { project.configure(this, configure) }

fun <T : Dependency> KotlinDependencyHandler.patches(dependency: T, configure: Closure<*>) =
    patches(dependency) { project.configure(this, configure) }
