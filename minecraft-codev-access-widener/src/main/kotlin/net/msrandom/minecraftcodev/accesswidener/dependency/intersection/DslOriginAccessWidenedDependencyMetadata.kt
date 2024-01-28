package net.msrandom.minecraftcodev.accesswidener.dependency.intersection

import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.component.ComponentSelector
import org.gradle.api.capabilities.Capability
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.component.local.model.DslOriginDependencyMetadata
import org.gradle.internal.component.model.*

class DslOriginAccessModifierIntersectionDependencyMetadata(
    val dependencies: List<LocalOriginDependencyMetadata>,
    private val source: Dependency
) :
    LocalOriginDependencyMetadata,
    DslOriginDependencyMetadata {
    override fun getSelector(): ComponentSelector {
        TODO("Not yet implemented")
    }

    override fun selectVariants(
        consumerAttributes: ImmutableAttributes,
        targetComponentState: ComponentGraphResolveState,
        consumerSchema: AttributesSchemaInternal,
        explicitRequestedCapabilities: MutableCollection<out Capability>
    ): VariantSelectionResult {
        TODO("Not yet implemented")
    }

    override fun getExcludes() = emptyList<ExcludeMetadata>()
    override fun getArtifacts() = emptyList<IvyArtifactName>()
    override fun withTarget(target: ComponentSelector) = this
    override fun withTargetAndArtifacts(target: ComponentSelector, artifacts: List<IvyArtifactName>) = this
    override fun isChanging() = dependencies.any(LocalOriginDependencyMetadata::isChanging)
    override fun isTransitive() = true
    override fun isConstraint() = false
    override fun isEndorsingStrictVersions() = false
    override fun getReason() = null
    override fun withReason(p0: String) = this
    override fun isForce() = false
    override fun getSource() = source
    override fun forced() = this
    override fun getModuleConfiguration() = null
    override fun getDependencyConfiguration() = Dependency.DEFAULT_CONFIGURATION
    override fun isFromLock() = false
}
