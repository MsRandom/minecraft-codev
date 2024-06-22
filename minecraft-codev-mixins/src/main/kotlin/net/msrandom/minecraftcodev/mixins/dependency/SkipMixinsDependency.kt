package net.msrandom.minecraftcodev.mixins.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

data class SkipMixinsDependency<T : ModuleDependency>(val sourceDependency: T) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) =
        dependency is SkipMixinsDependency<*> && sourceDependency.contentEquals(dependency.sourceDependency)

    @Suppress("UNCHECKED_CAST")
    override fun copy() = SkipMixinsDependency(sourceDependency.copy() as T)
}
