package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface MinecraftDependencyMetadata : DependencyMetadata

class MinecraftDependencyMetadataWrapper(private val delegate: DependencyMetadata) :
    MinecraftDependencyMetadata,
    DependencyMetadata by delegate

class DslOriginMinecraftDependencyMetadata(private val delegate: LocalOriginDependencyMetadata, private val source: Dependency) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    MinecraftDependencyMetadata {
    /**
     * This is simply a bug fix:
     * Kotlin MultiPlatform Plugin collects all dependencies requested in a configuration, along with transitive dependencies into a 'metadata' configuration
     * It then locks every dependency version to match
     * This would typically be fine, but in the case of Minecraft, which may be remapped or configured in various ways, a common source set may include a version that differs which would then be locked to a higher version
     * With that, it will have an invalid configuration(for example mappings for a different Minecraft versions), causing resolution errors
     *
     * Solution: Blocks target changing if this version is strict(which Minecraft's is by default) and the versions do not match.
     */
    private fun shouldModify(target: ComponentSelector): Boolean {
        val current = selector
        if (current is ModuleComponentSelector && target is ModuleComponentSelector) {
            if (current.versionConstraint.strictVersion.isEmpty()) {
                return true
            }

            return current.version == target.version
        }

        return true
    }

    override fun withTarget(target: ComponentSelector) =
        if (shouldModify(target)) {
            DslOriginMinecraftDependencyMetadata(delegate.withTarget(target), source)
        } else {
            this
        }

    override fun withTargetAndArtifacts(
        target: ComponentSelector,
        artifacts: List<IvyArtifactName>,
    ) = if (shouldModify(target)) {
        DslOriginMinecraftDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source)
    } else {
        this
    }

    override fun getSource() = source

    override fun forced() = DslOriginMinecraftDependencyMetadata(delegate.forced(), source)
}
