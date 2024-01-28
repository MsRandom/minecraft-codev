package net.msrandom.minecraftcodev.accesswidener.dependency.intersection

import org.gradle.api.Action
import org.gradle.api.artifacts.*
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.ImmutableVersionConstraint
import org.gradle.api.internal.artifacts.ModuleVersionSelectorStrictSpec
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint

interface AccessModifierIntersectionDependency : ExternalDependency {
    val dependencies: List<ModuleDependency>

    override fun copy(): AccessModifierIntersectionDependency
}

class AccessModifierIntersectionDependencyImpl(override val dependencies: List<ModuleDependency>) : AbstractModuleDependency(null), AccessModifierIntersectionDependency {
    private val module = DefaultModuleIdentifier.newId(null, "access-widener-intersection")

    override fun contentEquals(dependency: Dependency) = dependency is AccessModifierIntersectionDependency &&
            dependencies.size == dependency.dependencies.size &&
            dependencies.withIndex().all { (index, intersected) -> intersected.contentEquals(dependency.dependencies[index]) }

    override fun matchesStrictly(identifier: ModuleVersionIdentifier) = ModuleVersionSelectorStrictSpec(this).isSatisfiedBy(identifier)

    override fun getModule(): ModuleIdentifier = module

    override fun isForce() = false

    @Deprecated("accessWideners.intersection dependencies do not support forced versions")
    override fun setForce(force: Boolean): ExternalDependency {
        throw UnsupportedOperationException()
    }

    override fun version(action: Action<in MutableVersionConstraint>) = throw UnsupportedOperationException()

    override fun getVersionConstraint(): ImmutableVersionConstraint = DefaultImmutableVersionConstraint.of()

    @Suppress("UNCHECKED_CAST")
    override fun copy() = AccessModifierIntersectionDependencyImpl(dependencies.map(Dependency::copy) as List<ModuleDependency>)

    override fun getGroup(): String? = module.group
    override fun getName(): String = module.name
    override fun getVersion() = null
}
