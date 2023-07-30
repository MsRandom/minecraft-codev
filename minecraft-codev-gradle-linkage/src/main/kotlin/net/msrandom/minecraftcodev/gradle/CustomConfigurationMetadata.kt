package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.capabilities.CapabilitiesMetadata
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.Describables
import org.gradle.internal.DisplayName
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.internal.impldep.com.google.common.collect.ImmutableSet
import javax.inject.Inject

@Suppress("unused")
internal open class CustomConfigurationMetadata @Inject constructor(
    private val name: String,
    private val componentId: ModuleComponentIdentifier,
    private val dependencies: List<DependencyMetadata>,
    private val artifacts: List<ComponentArtifactMetadata>,
    private val attributes: ImmutableAttributes,
    private val capabilities: CapabilitiesMetadata,
    private val hierarchy: Set<String>
) : ConfigurationMetadata, VariantResolveMetadata {
    override fun getName() = name
    override fun getIdentifier() = null
    override fun asDescribable(): DisplayName = Describables.of(componentId, "variant", name)
    override fun getDependencies() = dependencies
    override fun getArtifacts(): ImmutableList<ComponentArtifactMetadata> = ImmutableList.copyOf(artifacts)
    override fun getAttributes() = attributes
    override fun getCapabilities() = capabilities
    override fun isExternalVariant() = false
    override fun getVariants() = setOf(this)
    override fun getExcludes(): ImmutableList<ExcludeMetadata> = ImmutableList.of()
    override fun isTransitive() = true
    override fun isVisible() = true
    override fun isCanBeConsumed() = true
    override fun getConsumptionDeprecation() = null
    override fun isCanBeResolved() = false
    override fun artifact(artifact: IvyArtifactName) = DefaultModuleComponentArtifactMetadata(componentId, artifact)
    override fun getHierarchy(): ImmutableSet<String> = ImmutableSet.copyOf(hierarchy)
}
