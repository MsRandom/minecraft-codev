package net.msrandom.minecraftcodev.includes.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

data class ExtractIncludesDependency<T : ModuleDependency>(val sourceDependency: T) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) =
        dependency is ExtractIncludesDependency<*> && sourceDependency.contentEquals(dependency.sourceDependency)

    @Suppress("UNCHECKED_CAST")
    override fun copy() = ExtractIncludesDependency(sourceDependency.copy() as T)
}
