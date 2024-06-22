package net.msrandom.minecraftcodev.gradle.api

import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.IvyArtifactName
import org.gradle.internal.resolve.result.BuildableArtifactResolveResult
import java.util.function.BiFunction

typealias ArtifactProvider = BiFunction<ComponentArtifactMetadata, IvyArtifactName?, BuildableArtifactResolveResult>

data class ComponentMetadataHolder(
    val id: ModuleComponentIdentifier,
    val attributes: ImmutableAttributes,
    val moduleVersionId: ModuleVersionIdentifier,
    val variants: List<VariantMetadataHolder>,
    val isChanging: Boolean,
    val status: String,
    val statusScheme: List<String>,
) : GradleSerializable {
    override fun serialize(serializer: GradleCommunicationProtocol.Serializer) {
        serializer.put(id)
        serializer.put(attributes)
        serializer.put(moduleVersionId)
        serializer.putList(variants)
        serializer.put(isChanging)
        serializer.put(status)
        serializer.put(statusScheme)
    }

    companion object {
        fun <T> deserialize(
            deserializer: GradleCommunicationProtocol.Deserializer,
        ) where T : ModuleComponentIdentifier, T : GradleSerializable =
            ComponentMetadataHolder(
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.getList { VariantMetadataHolder.deserialize(deserializer) },
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
            )
    }
}

abstract class DelegateComponentMetadataHolder<T, U : DelegateComponentMetadataHolder<T, U>>(val id: T) where T : ModuleComponentIdentifier {
    abstract val type: Class<U>

    abstract fun wrapVariant(
        variant: VariantMetadataHolder,
        artifactProvider: ArtifactProvider,
    ): VariantMetadata

    open fun extraVariants(existing: List<VariantMetadataHolder>) = emptyList<VariantMetadata>()
}
