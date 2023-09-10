package net.msrandom.minecraftcodev.decompiler.dependency

import org.gradle.api.Action
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.SelfResolvingDependency

val <T : ModuleDependency> T.withSources
    get() = withSources()

val SelfResolvingDependency.withSources
    get() = withSources()

@JvmOverloads
fun <T : ModuleDependency> T.withSources(configure: Action<T>? = null) = getWithSources(configure)

fun SelfResolvingDependency.withSources(): SelfResolvingDependency = TODO("Not yet implemented")

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getWithSources(configure: Action<T>?): DecompiledDependency<T> = DecompiledDependency(copy() as T).apply {
    configure?.execute(sourceDependency)
}
