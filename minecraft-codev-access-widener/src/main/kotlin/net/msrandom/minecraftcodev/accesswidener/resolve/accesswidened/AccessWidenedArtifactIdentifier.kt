package net.msrandom.minecraftcodev.accesswidener.resolve.accesswidened

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class AccessWidenedArtifactIdentifier(val id: ComponentArtifactIdentifier, private val accessWidenersHash: HashCode, private val originalHash: HashCode) {
    object ArtifactSerializer : Serializer<AccessWidenedArtifactIdentifier> {
        override fun read(decoder: Decoder) = AccessWidenedArtifactIdentifier(
            artifactIdSerializer.read(decoder),
            HashCode.fromBytes(decoder.readBinary()),
            HashCode.fromBytes(decoder.readBinary())
        )

        override fun write(encoder: Encoder, value: AccessWidenedArtifactIdentifier) {
            artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.accessWidenersHash.toByteArray())
            encoder.writeBinary(value.originalHash.toByteArray())
        }
    }
}
