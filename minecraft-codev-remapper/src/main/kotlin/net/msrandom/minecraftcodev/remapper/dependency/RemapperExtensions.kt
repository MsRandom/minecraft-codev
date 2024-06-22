package net.msrandom.minecraftcodev.remapper.dependency

import net.msrandom.minecraftcodev.core.utils.disambiguateName
import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import org.gradle.api.Action
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.SelfResolvingDependency
import org.gradle.api.tasks.SourceSet

val SourceSet.mappingsConfigurationName get() = disambiguateName(MinecraftCodevRemapperPlugin.MAPPINGS_CONFIGURATION)

val <T : ModuleDependency> T.remapped
    get() = remapped()

val SelfResolvingDependency.remapped
    get() = remapped()

@JvmOverloads
fun <T : ModuleDependency> T.remapped(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getRemapped(arguments, configure)

fun SelfResolvingDependency.remapped(arguments: Map<String, Any>) = getRemapped(arguments)

fun <T : ModuleDependency> T.remapped(
    sourceNamespace: Any? = "",
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String = "",
    configure: Action<T>? = null,
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration, configure)

fun SelfResolvingDependency.remapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null,
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration)

@JvmOverloads
fun <T : ModuleDependency> T.getRemapped(
    arguments: Map<String, Any>,
    configure: Action<T>? = null,
) = getRemapped(
    arguments["sourceNamespace"],
    arguments.getOrDefault("targetNamespace", MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE),
    arguments["mappingsConfiguration"]?.toString().orEmpty(),
    configure,
)

fun SelfResolvingDependency.getRemapped(arguments: Map<String, Any>) =
    getRemapped(
        arguments["sourceNamespace"],
        arguments.getOrDefault("targetNamespace", MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE),
        arguments["mappingsConfiguration"]?.toString(),
    )

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getRemapped(
    sourceNamespace: Any? = "",
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String = "",
    configure: Action<T>? = null,
): RemappedDependency<T> =
    RemappedDependency(copy() as T, sourceNamespace?.toString().orEmpty(), targetNamespace.toString(), mappingsConfiguration).apply {
        configure?.execute(sourceDependency)
    }

fun SelfResolvingDependency.getRemapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null,
): SelfResolvingDependency = TODO("Not yet implemented")
