package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
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
    override fun withTarget(target: ComponentSelector) =
        DslOriginMinecraftDependencyMetadata(delegate.withTarget(target), source)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginMinecraftDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source)

    override fun getSource() = source
    override fun forced() = DslOriginMinecraftDependencyMetadata(delegate.forced(), source)
}
