package net.msrandom.minecraftcodev.includes.resolve

import net.msrandom.minecraftcodev.core.resolve.MinecraftArtifactResolver.Companion.artifactIdSerializer
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class ExtractedIncludeArtifactIdentifier(val owner: ComponentArtifactIdentifier, private val artifactHash: HashCode, private val path: String) {
    object ArtifactSerializer : Serializer<ExtractedIncludeArtifactIdentifier> {
        override fun read(decoder: Decoder) =
            ExtractedIncludeArtifactIdentifier(
                artifactIdSerializer.read(decoder),
                HashCode.fromBytes(decoder.readBinary()),
                decoder.readString(),
            )

        override fun write(
            encoder: Encoder,
            value: ExtractedIncludeArtifactIdentifier,
        ) {
            artifactIdSerializer.write(encoder, value.owner)
            encoder.writeBinary(value.artifactHash.toByteArray())
            encoder.writeString(value.path)
        }
    }
}
