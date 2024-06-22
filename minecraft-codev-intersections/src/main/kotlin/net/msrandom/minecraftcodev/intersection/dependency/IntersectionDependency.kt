package net.msrandom.minecraftcodev.intersection.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency

sealed interface IntersectionDependency : Dependency {
    val dependencies: List<ModuleDependency>

    override fun copy(): IntersectionDependency
}

data class IntersectionDependencyImpl(override val dependencies: List<ModuleDependency>) :
    AbstractModuleDependency(null),
    IntersectionDependency {
    private fun joinDependencyParts(part: (ModuleDependency) -> String?) =
        dependencies
            .asSequence()
            .mapNotNull(part)
            .sorted()
            .distinct()
            .joinToString("-")

    override fun getGroup() = joinDependencyParts(ModuleDependency::getGroup)

    override fun getName() = "${joinDependencyParts(ModuleDependency::getName)}-intersection"

    override fun getVersion() = joinDependencyParts(ModuleDependency::getVersion)

    override fun contentEquals(dependency: Dependency) =
        this === dependency || (
            dependency is IntersectionDependencyImpl &&
                isCommonContentEquals(dependency) &&
                dependency.dependencies.zip(dependencies).all { (a, b) -> a.contentEquals(b) }
            )

    override fun copy() = IntersectionDependencyImpl(dependencies).also { copyTo(it) }

    override fun equals(other: Any?) = other is Dependency && contentEquals(other)

    override fun hashCode() = getName().hashCode() * 31 + version.hashCode() + 961
}
