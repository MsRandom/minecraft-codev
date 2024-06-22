package net.msrandom.minecraftcodev.intersection.resolve

import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer

data class IntersectionArtifactIdentifier(private val intersectionArtifactsHashCode: HashCode) {
    object ArtifactSerializer : Serializer<IntersectionArtifactIdentifier> {
        override fun read(decoder: Decoder) = IntersectionArtifactIdentifier(HashCode.fromBytes(decoder.readBinary()))

        override fun write(
            encoder: Encoder,
            value: IntersectionArtifactIdentifier,
        ) {
            encoder.writeBinary(value.intersectionArtifactsHashCode.toByteArray())
        }
    }
}
