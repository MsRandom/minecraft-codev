package net.msrandom.minecraftcodev.accesswidener

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

class AccessWidenedDependency<T : ModuleDependency>(
    val sourceDependency: T,
    internal val accessWidenersConfiguration: String?
) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) = dependency is AccessWidenedDependency<*> &&
            sourceDependency.contentEquals(dependency.sourceDependency) &&
            accessWidenersConfiguration == dependency.accessWidenersConfiguration

    @Suppress("UNCHECKED_CAST")
    override fun copy() = AccessWidenedDependency(sourceDependency.copy() as T, accessWidenersConfiguration)
}
