package net.msrandom.minecraftcodev.remapper.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class RemappedArtifactIdentifier(val id: ComponentArtifactIdentifier, private val namespace: String, private val mappingsHash: HashCode, private val unmappedHash: HashCode) {
    object ArtifactSerializer : Serializer<RemappedArtifactIdentifier> {
        override fun read(decoder: Decoder) = RemappedArtifactIdentifier(
            artifactIdSerializer.read(decoder),
            decoder.readString(),
            HashCode.fromBytes(decoder.readBinary()),
            HashCode.fromBytes(decoder.readBinary())
        )

        override fun write(encoder: Encoder, value: RemappedArtifactIdentifier) {
            artifactIdSerializer.write(encoder, value.id)
            encoder.writeString(value.namespace)
            encoder.writeBinary(value.mappingsHash.toByteArray())
            encoder.writeBinary(value.unmappedHash.toByteArray())
        }
    }
}
