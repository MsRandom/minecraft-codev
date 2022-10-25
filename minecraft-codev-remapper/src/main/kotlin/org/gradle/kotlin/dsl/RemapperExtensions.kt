package org.gradle.kotlin.dsl

import net.msrandom.minecraftcodev.remapper.MinecraftCodevRemapperPlugin
import net.msrandom.minecraftcodev.remapper.dependency.RemappedDependency
import org.gradle.api.Action
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.ModuleDependency

val <T : ModuleDependency> T.remapped
    get() = remapped()

val FileCollectionDependency.remapped
    get() = remapped()

@JvmOverloads
fun <T : ModuleDependency> T.remapped(arguments: Map<String, Any>, configure: Action<T>? = null) = getRemapped(arguments, configure)

fun FileCollectionDependency.remapped(arguments: Map<String, Any>) = getRemapped(arguments)

fun <T : ModuleDependency> T.remapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null,
    configure: Action<T>? = null
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration, configure)

fun FileCollectionDependency.remapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null
) = getRemapped(sourceNamespace, targetNamespace, mappingsConfiguration)

@JvmOverloads
fun <T : ModuleDependency> T.getRemapped(arguments: Map<String, Any>, configure: Action<T>? = null) =
    getRemapped(arguments["sourceNamespace"], arguments.getOrDefault("targetNamespace", MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE), arguments["mappingsConfiguration"]?.toString(), configure)

fun FileCollectionDependency.getRemapped(arguments: Map<String, Any>) =
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

fun FileCollectionDependency.getRemapped(
    sourceNamespace: Any? = null,
    targetNamespace: Any = MinecraftCodevRemapperPlugin.NAMED_MAPPINGS_NAMESPACE,
    mappingsConfiguration: String? = null
): FileCollectionDependency = TODO("Not yet implemented")
