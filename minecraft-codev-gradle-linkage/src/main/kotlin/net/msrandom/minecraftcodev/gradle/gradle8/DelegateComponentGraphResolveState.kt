package net.msrandom.minecraftcodev.gradle.gradle8

import net.msrandom.minecraftcodev.gradle.api.ArtifactProvider
import net.msrandom.minecraftcodev.gradle.api.GradleCommunicationProtocol
import net.msrandom.minecraftcodev.gradle.api.GradleSerializedData
import net.msrandom.minecraftcodev.gradle.api.VariantMetadata
import net.msrandom.minecraftcodev.gradle.api.VariantMetadataHolder
import net.msrandom.minecraftcodev.gradle.api.WrappedVariantMetadata
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.attributes.AttributesSchemaInternal
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.api.model.ObjectFactory
import org.gradle.internal.component.external.model.VirtualComponentIdentifier
import org.gradle.internal.component.model.ComponentGraphResolveMetadata
import org.gradle.internal.component.model.ComponentGraphResolveState
import org.gradle.internal.component.model.ComponentResolveMetadata
import org.gradle.internal.component.model.ConfigurationGraphResolveMetadata
import org.gradle.internal.component.model.ConfigurationMetadata
import org.gradle.internal.component.model.ModuleSources
import org.gradle.internal.component.model.VariantGraphResolveMetadata
import org.gradle.internal.impldep.com.google.common.base.Optional
import org.gradle.internal.impldep.com.google.common.collect.ImmutableList
import org.gradle.internal.resolve.resolver.ArtifactResolver
import org.gradle.internal.resolve.result.DefaultBuildableArtifactResolveResult
import java.util.function.BiFunction
import java.util.function.Function
import javax.inject.Inject

@Suppress("unused")
open class DelegateComponentGraphResolveState
@Inject
constructor(
    private val state: ComponentGraphResolveState,
    private val id: ModuleComponentIdentifier,
    private val variantWrapper: BiFunction<GradleSerializedData, ArtifactProvider, GradleSerializedData>,
    private val extraVariants: Function<List<GradleSerializedData>, List<GradleSerializedData>>,
    private val artifactResolver: ArtifactResolver,
    private val sourceLoader: ClassLoader,
    private val moduleSources: ModuleSources,
    private val attributesSchema: AttributesSchemaInternal,
    private val objectFactory: ObjectFactory,
) : ComponentResolveMetadata,
    ComponentGraphResolveMetadata {
    private val metadata get() = state.metadata

    private fun artifactProvider(variant: ConfigurationMetadata): ArtifactProvider {
        val artifactResolveMetadata = state.prepareForArtifactResolution().resolveMetadata

        return ArtifactProvider { artifact, overrideName ->
            val result = DefaultBuildableArtifactResolveResult()

            val overriddenArtifact = overrideName?.let(variant::artifact) ?: artifact

            artifactResolver.resolveArtifact(artifactResolveMetadata, overriddenArtifact, result)

            result
        }
    }

    override fun getAttributes(): ImmutableAttributes = (metadata as ComponentResolveMetadata).attributes

    override fun getId() = id

    override fun getModuleVersionId(): ModuleVersionIdentifier = metadata.moduleVersionId

    override fun getSources(): ModuleSources = (metadata as ComponentResolveMetadata).sources

    override fun withSources(sources: ModuleSources): ComponentResolveMetadata =
        DelegateComponentGraphResolveState(
            state,
            id,
            variantWrapper,
            extraVariants,
            artifactResolver,
            sourceLoader,
            moduleSources,
            attributesSchema,
            objectFactory,
        )

    override fun getAttributesSchema() = attributesSchema

    override fun getConfigurationNames() = emptySet<String>()

    override fun isMissing() = false

    override fun isChanging() = metadata.isChanging

    override fun getStatus() = metadata.status

    override fun getStatusScheme(): MutableList<String>? = (metadata as ComponentResolveMetadata).statusScheme

    override fun getPlatformOwners(): ImmutableList<VirtualComponentIdentifier> = ImmutableList.of()

    private fun wrapVariant(variant: VariantGraphResolveMetadata) =
        wrapVariantInternal(metadata.id as ModuleComponentIdentifier, variant)

    private fun unwrapVariant(
        holder: VariantMetadata,
        variant: VariantGraphResolveMetadata?,
    ): VariantGraphResolveMetadata =
        when (holder) {
            is WrappedVariantMetadata -> DelegateVariantGraphResolveMetadata(variant!!, holder)
            is VariantMetadataHolder -> CustomVariantGraphResolveMetadata(holder)
        }

    override fun getVariantsForGraphTraversal(): Optional<List<VariantGraphResolveMetadata>> =
        metadata.variantsForGraphTraversal.transform {
            val serializedBaseVariants =
                it.map { variant ->
                    val serializer =
                        GradleCommunicationProtocol.Serializer().apply {
                            wrapVariant(variant).serialize(this)
                        }

                    variant to serializer.data
                }

            val serializedWrappedBaseVariants =
                serializedBaseVariants.map { (variant, data) ->
                    variantWrapper.apply(data, artifactProvider(variant as ConfigurationMetadata))
                }

            val serializedExtraVariants = extraVariants.apply(serializedBaseVariants.map { (_, data) -> data })

            (serializedWrappedBaseVariants + serializedExtraVariants).map { serialized ->
                val variant = VariantMetadata.deserialize(GradleCommunicationProtocol.Deserializer(serialized))

                unwrapVariant(variant, it.firstOrNull { v -> v.name == v.name })
            }
        }

    override fun getConfiguration(name: String) =
        metadata.getConfiguration(name)?.let {
            val serializer =
                GradleCommunicationProtocol.Serializer().apply {
                    wrapVariant(it).serialize(this)
                }

            val wrappedData = variantWrapper.apply(serializer.data, artifactProvider(it as ConfigurationMetadata))
            val wrapped = VariantMetadata.deserialize(GradleCommunicationProtocol.Deserializer(wrappedData))

            unwrapVariant(wrapped, it) as ConfigurationGraphResolveMetadata
        }

    companion object {
        private fun wrapVariantInternal(
            identifier: ModuleComponentIdentifier,
            variant: VariantGraphResolveMetadata,
        ): VariantMetadataHolder {
            val name = variant.name
            val dependencies = variant.dependencies
            val attributes = variant.attributes
            val capabilities = variant.capabilities

            variant as ConfigurationMetadata

            return VariantMetadataHolder(
                name,
                identifier,
                variant.asDescribable(),
                dependencies,
                variant.artifacts,
                attributes,
                capabilities,
                variant.hierarchy,
            )
        }

        @JvmStatic
        fun wrapVariant(
            identifier: ModuleComponentIdentifier,
            variant: VariantGraphResolveMetadata,
        ): GradleSerializedData {
            val metadata = wrapVariantInternal(identifier, variant)

            val serializer = GradleCommunicationProtocol.Serializer()
            metadata.serialize(serializer)

            return serializer.data
        }
    }
}
