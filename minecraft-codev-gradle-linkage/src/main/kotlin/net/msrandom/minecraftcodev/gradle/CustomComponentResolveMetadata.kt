package net.msrandom.minecraftcodev.gradle

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.component.external.model.VirtualComponentIdentifier
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

internal open class CustomComponentResolveMetadata @Inject constructor(
    private val attributes: ImmutableAttributes,
    private val id: ModuleComponentIdentifier,
    private val moduleVersionId: ModuleVersionIdentifier,
    private val variants: List<ConfigurationMetadata>,
    private val isChanging: Boolean,
    private val status: String,
    private val statusScheme: List<String>,
    private val sources: ModuleSources,

    private val attributesSchema: AttributesSchemaInternal,
) : ComponentResolveMetadata {
    override fun getAttributes() = attributes
    override fun getId() = id
    override fun getModuleVersionId() = moduleVersionId
    override fun getSources() = sources

    override fun withSources(sources: ModuleSources) = CustomComponentResolveMetadata(
        attributes,
        id,
        moduleVersionId,
        variants,
        isChanging,
        status,
        statusScheme,
        sources,
        attributesSchema
    )

    override fun getAttributesSchema() = attributesSchema
    override fun getConfigurationNames() = emptySet<String>()
    override fun getConfiguration(name: String) = null

    override fun getVariantsForGraphTraversal(): Optional<ImmutableList<out ConfigurationMetadata>> =
        Optional.of(ImmutableList.copyOf(variants) as ImmutableList<out ConfigurationMetadata>)

    override fun isMissing() = false
    override fun isChanging() = isChanging
    override fun getStatus() = status
    override fun getStatusScheme() = statusScheme
    override fun getPlatformOwners(): ImmutableList<VirtualComponentIdentifier> = ImmutableList.of()
}
