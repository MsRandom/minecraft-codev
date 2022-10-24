package net.msrandom.minecraftcodev.forge

import org.gradle.api.internal.artifacts.metadata.ModuleComponentFileArtifactIdentifierSerializer
import org.gradle.internal.component.external.model.ModuleComponentFileArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class PatchedArtifactIdentifier(val id: ModuleComponentFileArtifactIdentifier, private val patchesHash: HashCode) {
    object ArtifactSerializer : Serializer<PatchedArtifactIdentifier> {
        private val artifactSerializer = ModuleComponentFileArtifactIdentifierSerializer()

        override fun read(decoder: Decoder) = PatchedArtifactIdentifier(artifactSerializer.read(decoder), HashCode.fromBytes(decoder.readBinary()))

        override fun write(encoder: Encoder, value: PatchedArtifactIdentifier) {
            artifactSerializer.write(encoder, value.id)
            encoder.writeBinary(value.patchesHash.toByteArray())
        }
    }
}
