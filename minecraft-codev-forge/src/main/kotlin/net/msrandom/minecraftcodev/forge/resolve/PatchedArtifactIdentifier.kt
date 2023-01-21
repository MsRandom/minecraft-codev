package net.msrandom.minecraftcodev.forge.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class PatchedArtifactIdentifier(val id: ComponentArtifactIdentifier, private val patchesHash: HashCode) {
    object ArtifactSerializer : Serializer<PatchedArtifactIdentifier> {
        override fun read(decoder: Decoder) = PatchedArtifactIdentifier(MinecraftArtifactResolver.artifactIdSerializer.read(decoder), HashCode.fromBytes(decoder.readBinary()))

        override fun write(encoder: Encoder, value: PatchedArtifactIdentifier) {
            MinecraftArtifactResolver.artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.patchesHash.toByteArray())
        }
    }
}
