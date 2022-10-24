package net.msrandom.minecraftcodev.core.resolve

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.internal.component.model.DependencyMetadata
import org.gradle.internal.component.model.ForcingDependencyMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.component.model.LocalOriginDependencyMetadata

class DependencyMetadataLocalWrapper @JvmOverloads constructor(
    private val delegate: DependencyMetadata,
    private val moduleConfiguration: String,
    private val dependencyConfiguration: String?,
    private val isForce: Boolean = delegate is ForcingDependencyMetadata && delegate.isForce
) : LocalOriginDependencyMetadata, DependencyMetadata by delegate {
    override fun withTarget(target: ComponentSelector) =
        DependencyMetadataLocalWrapper(delegate.withTarget(target), getModuleConfiguration(), getDependencyConfiguration(), isForce)

    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) =
        DependencyMetadataLocalWrapper(delegate.withTargetAndArtifacts(target, artifacts), getModuleConfiguration(), getDependencyConfiguration(), isForce)

    override fun isForce() = isForce

    override fun forced() =
        DependencyMetadataLocalWrapper(delegate, getModuleConfiguration(), getDependencyConfiguration(), true)

    override fun getModuleConfiguration() = moduleConfiguration
    override fun getDependencyConfiguration() = dependencyConfiguration?.takeIf(String::isNotEmpty) ?: Dependency.DEFAULT_CONFIGURATION
    override fun isFromLock() = false

    companion object {
        operator fun get(delegate: DependencyMetadata, moduleConfiguration: String, dependencyConfiguration: String?) =
            if (delegate is LocalOriginDependencyMetadata) delegate else DependencyMetadataLocalWrapper(delegate, moduleConfiguration, dependencyConfiguration)
    }
}
