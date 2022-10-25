package net.msrandom.minecraftcodev.accesswidener

import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface AccessWidenedDependencyMetadata : ConfiguredDependencyMetadata {
    val delegate: DependencyMetadata
}

class AccessWidenedDependencyMetadataWrapper(
    override val delegate: DependencyMetadata,
    override val relatedConfiguration: String?,
    private val moduleConfiguration: String?
) : AccessWidenedDependencyMetadata, DependencyMetadata by delegate {
    override fun getModuleConfiguration() = moduleConfiguration
}

class DslOriginAccessWidenedDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency,
    override val relatedConfiguration: String?
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    AccessWidenedDependencyMetadata {

    override fun withTarget(target: ComponentSelector) =
        DslOriginAccessWidenedDependencyMetadata(delegate.withTarget(target), source, relatedConfiguration)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginAccessWidenedDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source, relatedConfiguration)

    override fun getSource() = source
    override fun forced() = DslOriginAccessWidenedDependencyMetadata(delegate.forced(), source, relatedConfiguration)
}
