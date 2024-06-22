package net.msrandom.minecraftcodev.decompiler.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

data class DecompiledDependency<T : ModuleDependency>(val sourceDependency: T) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) =
        dependency is DecompiledDependency<*> && this.sourceDependency.contentEquals(dependency.sourceDependency)

    override fun copy() = DecompiledDependency(sourceDependency.copy())
}
