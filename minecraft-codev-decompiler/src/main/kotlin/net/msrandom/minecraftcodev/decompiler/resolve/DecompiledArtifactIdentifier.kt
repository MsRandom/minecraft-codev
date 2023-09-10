package net.msrandom.minecraftcodev.decompiler.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class DecompiledArtifactIdentifier(val id: ComponentArtifactIdentifier, private val artifactHash: HashCode) {
    object ArtifactSerializer : Serializer<DecompiledArtifactIdentifier> {
        override fun read(decoder: Decoder) = DecompiledArtifactIdentifier(
            artifactIdSerializer.read(decoder),
            HashCode.fromBytes(decoder.readBinary())
        )

        override fun write(encoder: Encoder, value: DecompiledArtifactIdentifier) {
            artifactIdSerializer.write(encoder, value.id)
            encoder.writeBinary(value.artifactHash.toByteArray())
        }
    }
}
