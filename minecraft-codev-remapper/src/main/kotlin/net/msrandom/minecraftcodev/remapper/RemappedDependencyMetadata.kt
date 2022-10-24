package net.msrandom.minecraftcodev.remapper

import net.msrandom.minecraftcodev.core.MappingsNamespace
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

sealed interface RemappedDependencyMetadata : DependencyMetadata {
    val delegate: DependencyMetadata
    val sourceNamespace: MappingsNamespace?
    val targetNamespace: MappingsNamespace
    val mappingsConfiguration: String?

    fun getModuleConfiguration(): String?
}

class RemappedDependencyMetadataWrapper(
    override val delegate: DependencyMetadata,
    override val sourceNamespace: MappingsNamespace?,
    override val targetNamespace: MappingsNamespace,
    override val mappingsConfiguration: String?,
    private val moduleConfiguration: String?
) : RemappedDependencyMetadata, DependencyMetadata by delegate {
    override fun getModuleConfiguration() = moduleConfiguration
}

class DslOriginRemappedDependencyMetadata(
    override val delegate: LocalOriginDependencyMetadata,
    private val source: Dependency,
    override val sourceNamespace: MappingsNamespace?,
    override val targetNamespace: MappingsNamespace,
    override val mappingsConfiguration: String?
) :
    LocalOriginDependencyMetadata by delegate,
    DslOriginDependencyMetadata,
    RemappedDependencyMetadata {

    override fun withTarget(target: ComponentSelector) =
        DslOriginRemappedDependencyMetadata(delegate.withTarget(target), source, sourceNamespace, targetNamespace, mappingsConfiguration)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DslOriginRemappedDependencyMetadata(delegate.withTargetAndArtifacts(target, artifacts), source, sourceNamespace, targetNamespace, mappingsConfiguration)

    override fun getSource() = source
    override fun forced() = DslOriginRemappedDependencyMetadata(delegate.forced(), source, sourceNamespace, targetNamespace, mappingsConfiguration)
}
