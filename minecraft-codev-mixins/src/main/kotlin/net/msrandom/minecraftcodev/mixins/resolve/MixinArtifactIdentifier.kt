package net.msrandom.minecraftcodev.mixins.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class MixinArtifactIdentifier(val id: ComponentArtifactIdentifier, private val mixinsHash: HashCode, private val originalHash: HashCode) {
    object ArtifactSerializer : Serializer<MixinArtifactIdentifier> {
        override fun read(decoder: Decoder) =
            MixinArtifactIdentifier(
                artifactIdSerializer.read(decoder),
                HashCode.fromBytes(decoder.readBinary()),
                HashCode.fromBytes(decoder.readBinary()),
            )

        override fun write(
            encoder: Encoder,
            value: MixinArtifactIdentifier,
        ) {
            artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.mixinsHash.toByteArray())
            encoder.writeBinary(value.originalHash.toByteArray())
        }
    }
}
