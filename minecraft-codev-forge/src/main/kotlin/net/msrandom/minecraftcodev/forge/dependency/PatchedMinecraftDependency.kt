package net.msrandom.minecraftcodev.forge.dependency

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalDependency

data class PatchedMinecraftDependency(val minecraftDependency: MinecraftDependency, val patches: String) : ExternalDependency by minecraftDependency {
    override fun contentEquals(dependency: Dependency) =
        dependency is PatchedMinecraftDependency &&
            minecraftDependency.contentEquals(
                dependency.minecraftDependency,
            ) && patches == dependency.patches

    override fun copy() = PatchedMinecraftDependency(minecraftDependency.copy(), patches)
}
