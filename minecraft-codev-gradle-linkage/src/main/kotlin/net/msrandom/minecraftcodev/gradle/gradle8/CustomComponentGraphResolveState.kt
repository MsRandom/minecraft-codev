package net.msrandom.minecraftcodev.gradle.gradle8

import net.msrandom.minecraftcodev.gradle.api.ComponentMetadataHolder
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.VirtualComponentIdentifier
import org.gradle.internal.component.model.*
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import javax.inject.Inject

@Suppress("unused")
open class CustomComponentGraphResolveMetadata @Inject constructor(
    private val metadata: ComponentMetadataHolder,

    private val moduleSources: ModuleSources,

    private val attributesSchema: AttributesSchemaInternal,
    private val objectFactory: ObjectFactory,
) : ComponentResolveMetadata, ComponentGraphResolveMetadata {
    override fun getAttributes() = metadata.attributes
    override fun getId() = metadata.id
    override fun getModuleVersionId() = metadata.moduleVersionId
    override fun getSources() = moduleSources

    override fun withSources(sources: ModuleSources): ComponentResolveMetadata =
        objectFactory.newInstance(CustomComponentGraphResolveMetadata::class.java, metadata, sources)

    override fun getAttributesSchema() = attributesSchema
    override fun getConfigurationNames() = emptySet<String>()
    override fun isMissing() = false
    override fun isChanging() = metadata.isChanging
    override fun getStatus() = metadata.status
    override fun getStatusScheme() = metadata.statusScheme

    override fun getPlatformOwners(): ImmutableList<VirtualComponentIdentifier> =
        ImmutableList.of()

    override fun getVariantsForGraphTraversal(): Optional<List<VariantGraphResolveMetadata>> =
        Optional.of(metadata.variants.map(::CustomVariantGraphResolveMetadata))

    override fun getConfiguration(name: String) = null
}
