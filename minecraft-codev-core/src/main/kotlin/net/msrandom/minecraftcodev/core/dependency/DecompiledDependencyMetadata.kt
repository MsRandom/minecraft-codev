package net.msrandom.minecraftcodev.core.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface DecompiledDependencyMetadata : DependencyMetadata {
    val delegate: DependencyMetadata
}

class DecompiledDependencyMetadataWrapper(override val delegate: DependencyMetadata) :
    DecompiledDependencyMetadata,
    DependencyMetadata by delegate

class DslOriginDecompiledDependencyMetadata(override val delegate: LocalOriginDependencyMetadata, private val source: Dependency) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    DecompiledDependencyMetadata {
    override fun withTarget(target: ComponentSelector) =
        DslOriginDecompiledDependencyMetadata(delegate.withTarget(target), source)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginDecompiledDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source)

    override fun getSource() = source
    override fun forced() = DslOriginDecompiledDependencyMetadata(delegate.forced(), source)
}
