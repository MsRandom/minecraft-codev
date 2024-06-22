package net.msrandom.minecraftcodev.accesswidener.dependency

import groovy.lang.Closure
import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.accessWidenersConfigurationName get() = disambiguateName(MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION)
val HasKotlinDependencies.accessWidenersConfigurationName get() =
    disambiguateName(
        MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION,
    )

val <T : ModuleDependency> T.accessWidened
    get() = accessWidened()

val FileCollectionDependency.accessWidened
    get() = accessWidened()

fun KotlinDependencyHandler.accessWideners(dependencyNotation: Any) =
    (this as DefaultKotlinDependencyHandler).let {
        it.project.dependencies.add(it.parent.accessWidenersConfigurationName, dependencyNotation)
    }

fun KotlinDependencyHandler.accessWideners(
    dependencyNotation: String,
    configure: ExternalModuleDependency.() -> Unit,
) = (accessWideners(dependencyNotation) as ExternalModuleDependency).also(configure)

fun <T : Dependency> KotlinDependencyHandler.accessWideners(
    dependency: T,
    configure: T.() -> Unit,
) = (this as DefaultKotlinDependencyHandler).let {
    configure(dependency)
    it.project.dependencies.add(it.parent.accessWidenersConfigurationName, dependency)
}

fun KotlinDependencyHandler.accessWideners(
    dependencyNotation: String,
    configure: Closure<*>,
) = accessWideners(dependencyNotation) { project.configure(this, configure) }

fun <T : Dependency> KotlinDependencyHandler.accessWideners(
    dependency: T,
    configure: Closure<*>,
) = accessWideners(dependency) { project.configure(this, configure) }

@JvmOverloads
fun <T : ModuleDependency> T.accessWidened(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getAccessWidened(arguments, configure)

fun FileCollectionDependency.accessWidened(arguments: Map<String, Any>) = getAccessWidened(arguments)

fun <T : ModuleDependency> T.accessWidened(
    accessWidenersConfiguration: String = "",
    configure: Action<T>? = null,
) = getAccessWidened(accessWidenersConfiguration, configure)

fun FileCollectionDependency.accessWidened(accessWidenersConfiguration: String? = null) = getAccessWidened(accessWidenersConfiguration)

@JvmOverloads
fun <T : ModuleDependency> T.getAccessWidened(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getAccessWidened(arguments["accessWidenersConfiguration"]?.toString().orEmpty(), configure)

fun FileCollectionDependency.getAccessWidened(arguments: Map<String, Any>) =
    getAccessWidened(arguments["accessWidenersConfiguration"]?.toString())

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getAccessWidened(
    accessWidenersConfiguration: String = "",
    configure: Action<T>? = null,
): AccessWidenedDependency<T> =
    AccessWidenedDependency(copy() as T, accessWidenersConfiguration).apply {
        configure?.execute(sourceDependency)
    }

fun FileCollectionDependency.getAccessWidened(accessWidenersConfiguration: String? = null): FileCollectionDependency =
    TODO("Not yet implemented")
