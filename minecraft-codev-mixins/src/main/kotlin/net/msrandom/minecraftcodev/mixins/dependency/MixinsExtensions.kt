package net.msrandom.minecraftcodev.mixins.dependency

import groovy.lang.Closure
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin
import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSet
import org.jetbrains.kotlin.gradle.plugin.HasKotlinDependencies
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler
import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinDependencyHandler

val SourceSet.mixinsConfigurationName get() = disambiguateName(MinecraftCodevMixinsPlugin.MIXINS_CONFIGURATION)
val HasKotlinDependencies.mixinsConfigurationName get() = disambiguateName(MinecraftCodevMixinsPlugin.MIXINS_CONFIGURATION)

val <T : ModuleDependency> T.mixin
    get() = mixin()

val FileCollectionDependency.mixin
    get() = mixin()

val <T : ModuleDependency> T.skipMixins
    get() = skipMixins()

val FileCollectionDependency.skipMixins
    get() = skipMixins()

fun KotlinDependencyHandler.mixins(dependencyNotation: Any) =
    (this as DefaultKotlinDependencyHandler).let {
        it.project.dependencies.add(it.parent.mixinsConfigurationName, dependencyNotation)
    }

fun KotlinDependencyHandler.mixins(
    dependencyNotation: String,
    configure: ExternalModuleDependency.() -> Unit,
) = (mixins(dependencyNotation) as ExternalModuleDependency).also(configure)

fun <T : Dependency> KotlinDependencyHandler.mixins(
    dependency: T,
    configure: T.() -> Unit,
) = (this as DefaultKotlinDependencyHandler).let {
    configure(dependency)
    it.project.dependencies.add(it.parent.mixinsConfigurationName, dependency)
}

fun KotlinDependencyHandler.mixins(
    dependencyNotation: String,
    configure: Closure<*>,
) = mixins(dependencyNotation) { project.configure(this, configure) }

fun <T : Dependency> KotlinDependencyHandler.mixins(
    dependency: T,
    configure: Closure<*>,
) = mixins(dependency) { project.configure(this, configure) }

@JvmOverloads
fun <T : ModuleDependency> T.mixin(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getMixin(arguments, configure)

fun FileCollectionDependency.mixin(arguments: Map<String, Any>) = getMixin(arguments)

fun <T : ModuleDependency> T.mixin(
    mixinsConfiguration: String = "",
    configure: Action<T>? = null,
) = getMixin(mixinsConfiguration, configure)

fun FileCollectionDependency.mixin(mixinsConfiguration: String? = null) = getMixin(mixinsConfiguration)

@JvmOverloads
fun <T : ModuleDependency> T.getMixin(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getMixin(arguments["mixinsConfiguration"]?.toString().orEmpty(), configure)

fun FileCollectionDependency.getMixin(arguments: Map<String, Any>) = getMixin(arguments["mixinsConfiguration"]?.toString())

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getMixin(
    mixinsConfiguration: String = "",
    configure: Action<T>? = null,
): MixinDependency<T> =
    MixinDependency(copy() as T, mixinsConfiguration).apply {
        configure?.execute(sourceDependency)
    }

fun FileCollectionDependency.getMixin(mixinsConfiguration: String? = null): FileCollectionDependency = TODO("Not yet implemented")

fun <T : ModuleDependency> T.skipMixins(configure: Action<T>? = null) = getSkipMixins(configure)

fun FileCollectionDependency.skipMixins(): FileCollectionDependency = TODO("Not yet implemented")

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getSkipMixins(configure: Action<T>? = null): SkipMixinsDependency<T> =
    SkipMixinsDependency(copy() as T).apply {
        configure?.execute(sourceDependency)
    }
