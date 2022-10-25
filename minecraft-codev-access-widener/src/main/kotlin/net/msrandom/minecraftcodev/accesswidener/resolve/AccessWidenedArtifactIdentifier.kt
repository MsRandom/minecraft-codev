package net.msrandom.minecraftcodev.accesswidener.resolve

import org.gradle.api.internal.artifacts.metadata.ModuleComponentFileArtifactIdentifierSerializer
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class AccessWidenedArtifactIdentifier(val id: ModuleComponentFileArtifactIdentifier, private val accessWidenersHash: HashCode, private val originalHash: HashCode) {
    object ArtifactSerializer : Serializer<AccessWidenedArtifactIdentifier> {
        private val artifactSerializer = ModuleComponentFileArtifactIdentifierSerializer()

        override fun read(decoder: Decoder) = AccessWidenedArtifactIdentifier(
            artifactSerializer.read(decoder),
            HashCode.fromBytes(decoder.readBinary()),
            HashCode.fromBytes(decoder.readBinary())
        )

        override fun write(encoder: Encoder, value: AccessWidenedArtifactIdentifier) {
            artifactSerializer.write(encoder, value.id)
            encoder.writeBinary(value.accessWidenersHash.toByteArray())
            encoder.writeBinary(value.originalHash.toByteArray())
        }
    }
}
