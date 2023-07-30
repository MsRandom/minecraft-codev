package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.resolve.minecraft.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class SkipMixinsArtifactIdentifier(val id: ComponentArtifactIdentifier, private val originalHash: HashCode) {
    object ArtifactSerializer : Serializer<SkipMixinsArtifactIdentifier> {
        override fun read(decoder: Decoder) = SkipMixinsArtifactIdentifier(
            artifactIdSerializer.read(decoder),
            HashCode.fromBytes(decoder.readBinary())
        )

        override fun write(encoder: Encoder, value: SkipMixinsArtifactIdentifier) {
            artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.originalHash.toByteArray())
        }
    }
}
