package net.msrandom.minecraftcodev.includes.dependency

import org.gradle.api.Action
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.SelfResolvingDependency

val <T : ModuleDependency> T.extractIncludes
    get() = extractIncludes()

val SelfResolvingDependency.extractIncludes
    get() = extractIncludes()

fun <T : ModuleDependency> T.extractIncludes(configure: Action<T>? = null) = getExtractIncludes(configure)

fun SelfResolvingDependency.extractIncludes(): SelfResolvingDependency = TODO("Not yet implemented")

@Suppress("UNCHECKED_CAST")
fun <T : ModuleDependency> T.getExtractIncludes(configure: Action<T>? = null): ExtractIncludesDependency<T> =
    ExtractIncludesDependency(copy() as T).apply {
        configure?.execute(sourceDependency)
    }
