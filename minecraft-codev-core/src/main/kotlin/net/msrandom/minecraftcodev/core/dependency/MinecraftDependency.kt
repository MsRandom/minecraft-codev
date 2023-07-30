package net.msrandom.minecraftcodev.core.dependency

import net.msrandom.minecraftcodev.core.resolve.minecraft.MinecraftComponentResolvers
import org.gradle.api.Action
import org.gradle.api.artifacts.*
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.ModuleVersionSelectorStrictSpec
import org.gradle.api.internal.artifacts.dependencies.AbstractModuleDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint

sealed interface MinecraftDependency : ExternalDependency {
    var isChanging: Boolean

    fun setChanging(changing: Boolean): MinecraftDependency
    override fun copy(): MinecraftDependency
}

class MinecraftDependencyImpl(private val name: String, version: String, configuration: String?) : AbstractModuleDependency(configuration), MinecraftDependency {
    override var isChanging = false

    private val versionConstraint = DefaultMutableVersionConstraint(version)
    private val module = DefaultModuleIdentifier.newId(MinecraftComponentResolvers.GROUP, name)

    private fun isContentEqualsFor(dependencyRhs: MinecraftDependencyImpl) =
        isCommonContentEquals(dependencyRhs) && isChanging == dependencyRhs.isChanging && getVersionConstraint() == dependencyRhs.versionConstraint

    override fun getGroup(): String = module.group
    override fun getName(): String = module.name
    override fun getVersion(): String = versionConstraint.version
    override fun contentEquals(dependency: Dependency) = this === dependency || dependency is MinecraftDependencyImpl && isContentEqualsFor(dependency)
    override fun copy() = MinecraftDependencyImpl(name, version, targetConfiguration).also { copyTo(it) }
    override fun matchesStrictly(identifier: ModuleVersionIdentifier) = ModuleVersionSelectorStrictSpec(this).isSatisfiedBy(identifier)
    override fun getModule(): ModuleIdentifier = module
    override fun isForce() = false

    @Deprecated("Dependency constraints should be used instead")
    override fun setForce(force: Boolean): ExternalDependency {
        throw UnsupportedOperationException("minecraft() dependencies don't support force, use dependency constraints instead.")
    }

    override fun version(configureAction: Action<in MutableVersionConstraint>) = configureAction.execute(versionConstraint)
    override fun getVersionConstraint() = versionConstraint

    override fun setChanging(changing: Boolean): MinecraftDependency {
        isChanging = changing
        return this
    }

    override fun equals(other: Any?) = other is Dependency && contentEquals(other)
    override fun hashCode() = getName().hashCode() * 31 + version.hashCode() + 961
}
