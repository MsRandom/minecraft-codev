package net.msrandom.minecraftcodev.core.resolve.minecraft

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.msrandom.minecraftcodev.core.URISerializer
import java.net.URI
import java.time.OffsetDateTime

@Serializable
data class MinecraftVersionManifest(val latest: Map<String, String>, val versions: List<VersionInfo>) {
    @Serializable
    data class VersionInfo(
        val id: String,
        val type: String,
        @Serializable(URISerializer::class) val url: URI,
        val sha1: String?,
        @Serializable(OffsetDateTimeSerializer::class) val time: OffsetDateTime,
        @Serializable(OffsetDateTimeSerializer::class) val releaseTime: OffsetDateTime
    )

    @Serializer(OffsetDateTime::class)
    class OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
        override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
        override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
    }
}
