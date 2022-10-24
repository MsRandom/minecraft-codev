package net.msrandom.minecraftcodev.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI

@Serializer(URI::class)
object URISerializer : KSerializer<URI> {
    override fun deserialize(decoder: Decoder): URI = URI.create(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: URI) = encoder.encodeString(value.toString())
}
