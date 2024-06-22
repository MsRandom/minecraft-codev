package net.msrandom.minecraftcodev.accesswidener.dependency

import net.msrandom.minecraftcodev.accesswidener.MinecraftCodevAccessWidenerPlugin
import net.msrandom.minecraftcodev.core.utils.disambiguateName
import org.gradle.api.Action
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.tasks.SourceSet

val SourceSet.accessWidenersConfigurationName get() = disambiguateName(MinecraftCodevAccessWidenerPlugin.ACCESS_WIDENERS_CONFIGURATION)

val <T : ModuleDependency> T.accessWidened
    get() = accessWidened()

val FileCollectionDependency.accessWidened
    get() = accessWidened()

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
