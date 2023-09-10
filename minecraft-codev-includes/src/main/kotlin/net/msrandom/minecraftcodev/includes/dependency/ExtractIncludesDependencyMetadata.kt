package net.msrandom.minecraftcodev.includes.dependency

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface ExtractIncludesDependencyMetadata : DependencyMetadata {
    val delegate: DependencyMetadata
}

class ExtractIncludesDependencyMetadataWrapper(override val delegate: DependencyMetadata) :
    ExtractIncludesDependencyMetadata,
    DependencyMetadata by delegate

class DslOriginExtractIncludesDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    ExtractIncludesDependencyMetadata {

    override fun withTarget(target: ComponentSelector) =
        DslOriginExtractIncludesDependencyMetadata(delegate.withTarget(target), source)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginExtractIncludesDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source)

    override fun getSource() = source
    override fun forced() = DslOriginExtractIncludesDependencyMetadata(delegate.forced(), source)
}
