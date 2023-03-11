package net.msrandom.minecraftcodev.mixins.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

class MixinDependency<T : ModuleDependency>(
    val sourceDependency: T,
    internal val mixinsConfiguration: String?
) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) = dependency is MixinDependency<*> &&
            sourceDependency.contentEquals(dependency.sourceDependency) &&
            mixinsConfiguration == dependency.mixinsConfiguration

    @Suppress("UNCHECKED_CAST")
    override fun copy() = MixinDependency(sourceDependency.copy() as T, mixinsConfiguration)
}
