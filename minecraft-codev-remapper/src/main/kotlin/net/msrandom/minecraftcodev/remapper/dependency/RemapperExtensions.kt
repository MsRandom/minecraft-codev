package net.msrandom.minecraftcodev.remapper.dependency

import groovy.lang.Closure
import net.msrandom.minecraftcodev.core.utils.sourceSetName
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.mappingsConfigurationName get() = sourceSetName(name, MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION)
val HasKotlinDependencies.mappingsConfigurationName get() = sourceSetName((this as Named).name, MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION)

val <T : ModuleDependency> T.remapped
    get() = remapped()

val SelfResolvingDependency.remapped
    get() = remapped()

fun KotlinDependencyHandler.mappings(dependencyNotation: Any) = (this as DefaultKotlinDependencyHandler).let {
    it.project.dependencies.add(it.parent.mappingsConfigurationName, dependencyNotation)
}

fun KotlinDependencyHandler.mappings(dependencyNotation: String, configure: ExternalModuleDependency.() -> Unit) =
    (mappings(dependencyNotation) as ExternalModuleDependency).also(configure)

fun <T : Dependency> KotlinDependencyHandler.mappings(dependency: T, configure: T.() -> Unit) = (this as DefaultKotlinDependencyHandler).let {
    configure(dependency)
    it.project.dependencies.add(it.parent.mappingsConfigurationName, dependency)
}

fun KotlinDependencyHandler.mappings(dependencyNotation: String, configure: Closure<*>) =
    mappings(dependencyNotation) { project.configure(this, configure) }

fun <T : Dependency> KotlinDependencyHandler.mappings(dependency: T, configure: Closure<*>) =
    mappings(dependency) { project.configure(this, configure) }


@JvmOverloads
fun <T : ModuleDependency> T.remapped(arguments: Map<String, Any>, configure: Action<T>? = null) = getRemapped(arguments, configure)

fun SelfResolvingDependency.remapped(arguments: Map<String, Any>) = getRemapped(arguments)

fun <T : ModuleDependency> T.remapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null,
    configure: Action<T>? = null
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration, configure)

fun SelfResolvingDependency.remapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration)

@JvmOverloads
fun <T : ModuleDependency> T.getRemapped(arguments: Map<String, Any>, configure: Action<T>? = null) =
    getRemapped(
        arguments["sourceNamespace"],
        arguments.getOrDefault("targetNamespace", MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE),
        arguments["mappingsConfiguration"]?.toString(),
        configure
    )

fun SelfResolvingDependency.getRemapped(arguments: Map<String, Any>) =
    getRemapped(arguments["sourceNamespace"], arguments.getOrDefault("targetNamespace", MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE), arguments["mappingsConfiguration"]?.toString())

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getRemapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null,
    configure: Action<T>? = null
): RemappedDependency<T> = RemappedDependency(copy() as T, sourceNamespace?.toString(), targetNamespace.toString(), mappingsConfiguration).apply {
    configure?.execute(sourceDependency)
}

fun SelfResolvingDependency.getRemapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null
): SelfResolvingDependency = TODO("Not yet implemented")
