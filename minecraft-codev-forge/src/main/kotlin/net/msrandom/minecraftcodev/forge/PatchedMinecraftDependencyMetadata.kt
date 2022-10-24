package net.msrandom.minecraftcodev.forge

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface PatchedMinecraftDependencyMetadata : DependencyMetadata {
    val patches: String
}

class PatchedMinecraftDependencyMetadataWrapper(private val delegate: DependencyMetadata, override val patches: String) :
    PatchedMinecraftDependencyMetadata,
    DependencyMetadata by delegate

class DslOriginPatchedMinecraftDependencyMetadata(private val delegate: LocalOriginDependencyMetadata, private val source: Dependency, override val patches: String) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    PatchedMinecraftDependencyMetadata {
    override fun withTarget(target: ComponentSelector) =
        DslOriginPatchedMinecraftDependencyMetadata(delegate.withTarget(target), source, patches)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginPatchedMinecraftDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source, patches)

    override fun getSource() = source
    override fun forced() = DslOriginPatchedMinecraftDependencyMetadata(delegate.forced(), source, patches)
}
