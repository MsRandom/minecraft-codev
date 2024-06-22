package net.msrandom.minecraftcodev.remapper.dependency

import net.msrandom.minecraftcodev.core.dependency.ConfiguredDependencyMetadata
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface RemappedDependencyMetadata : ConfiguredDependencyMetadata {
    val delegate: DependencyMetadata
    val sourceNamespace: String
    val targetNamespace: String
}

class RemappedDependencyMetadataWrapper(
    override val delegate: DependencyMetadata,
    private val selector: ComponentSelector,
    override val sourceNamespace: String,
    override val targetNamespace: String,
    override val relatedConfiguration: String,
) : RemappedDependencyMetadata, DependencyMetadata by delegate {
    override fun getSelector() = selector
}

class DslOriginRemappedDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency,
    override val sourceNamespace: String,
    override val targetNamespace: String,
    override val relatedConfiguration: String,
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    RemappedDependencyMetadata {
    override fun withTarget(target: ComponentSelector) =
        DslOriginRemappedDependencyMetadata(delegate.withTarget(target), source, sourceNamespace, targetNamespace, relatedConfiguration)

    override fun withTargetAndArtifacts(
        target: ComponentSelector,
        artifacts: List<IvyArtifactName>,
    ) = DslOriginRemappedDependencyMetadata(
        delegate.withTargetAndArtifacts(target, artifacts),
        source,
        sourceNamespace,
        targetNamespace,
        relatedConfiguration,
    )

    override fun getSource() = source

    override fun forced() =
        DslOriginRemappedDependencyMetadata(
            delegate.forced(),
            source,
            sourceNamespace,
            targetNamespace,
            relatedConfiguration,
        )
}
