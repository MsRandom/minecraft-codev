package net.msrandom.minecraftcodev.remapper

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency

class RemappedDependency<T : ModuleDependency>(
    val sourceDependency: T,
    internal val sourceNamespace: String?,
    val targetNamespace: String,
    internal val mappingsConfiguration: String?
) : ModuleDependency by sourceDependency {
    override fun contentEquals(dependency: Dependency) = dependency is RemappedDependency<*> &&
            sourceDependency.contentEquals(dependency.sourceDependency) &&
            sourceNamespace == dependency.sourceNamespace &&
            targetNamespace == dependency.targetNamespace &&
            mappingsConfiguration == dependency.mappingsConfiguration

    @Suppress("UNCHECKED_CAST")
    override fun copy() = RemappedDependency(sourceDependency.copy() as T, sourceNamespace, targetNamespace, mappingsConfiguration)
}
