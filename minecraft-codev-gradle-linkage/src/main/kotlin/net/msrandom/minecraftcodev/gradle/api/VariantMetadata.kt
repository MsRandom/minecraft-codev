package net.msrandom.minecraftcodev.gradle.api

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.capabilities.CapabilitiesMetadata
import org.gradle.api.internal.attributes.ImmutableAttributes
import org.gradle.internal.DisplayName
import org.gradle.internal.component.model.ComponentArtifactMetadata
import org.gradle.internal.component.model.DependencyMetadata

sealed interface VariantMetadata : GradleSerializable {
    val name: String
    val componentId: ModuleComponentIdentifier

    companion object {
        fun serialize(
            serializer: GradleCommunicationProtocol.Serializer,
            metadata: VariantMetadata,
        ) {
            serializer.put(metadata is WrappedVariantMetadata)
            metadata.serialize(serializer)
        }

        fun deserialize(deserializer: GradleCommunicationProtocol.Deserializer) =
            if (deserializer.get()) {
                WrappedVariantMetadata.deserialize(deserializer)
            } else {
                VariantMetadataHolder.deserialize(deserializer)
            }
    }
}

data class VariantMetadataHolder(
    override val name: String,
    override val componentId: ModuleComponentIdentifier,
    val displayName: DisplayName,
    val dependencies: List<DependencyMetadata>,
    val artifacts: List<ComponentArtifactMetadata>,
    val attributes: ImmutableAttributes,
    val capabilities: CapabilitiesMetadata,
    val hierarchy: Set<String>,
) : VariantMetadata {
    override fun serialize(serializer: GradleCommunicationProtocol.Serializer) {
        serializer.put(name)
        serializer.put(componentId)
        serializer.put(displayName)
        serializer.put(dependencies)
        serializer.put(artifacts)
        serializer.put(attributes)
        serializer.put(capabilities)
        serializer.put(hierarchy)
    }

    companion object {
        fun deserialize(deserializer: GradleCommunicationProtocol.Deserializer) =
            VariantMetadataHolder(
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
            )
    }
}

data class WrappedVariantMetadata(
    override val name: String,
    override val componentId: ModuleComponentIdentifier,
    val attributes: ImmutableAttributes,
    val displayName: DisplayName,
    val dependencies: List<DependencyMetadata>,
    val artifacts: List<ComponentArtifactMetadata>,
) : VariantMetadata {
    override fun serialize(serializer: GradleCommunicationProtocol.Serializer) {
        serializer.put(name)
        serializer.put(componentId)
        serializer.put(attributes)
        serializer.put(displayName)
        serializer.put(dependencies)
        serializer.put(artifacts)
    }

    companion object {
        fun deserialize(deserializer: GradleCommunicationProtocol.Deserializer) =
            WrappedVariantMetadata(
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
                deserializer.get(),
            )
    }
}
