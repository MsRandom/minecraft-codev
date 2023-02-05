package net.msrandom.minecraftcodev.core.repository

import org.gradle.internal.hash.HashCode
import org.gradle.internal.resource.cached.CachedExternalResource
import org.gradle.internal.resource.cached.DefaultCachedExternalResource
import org.gradle.internal.resource.metadata.DefaultExternalResourceMetaData
import org.gradle.internal.resource.metadata.ExternalResourceMetaData
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Path

data class CachedExternalResourceSerializer(private val commonRootPath: Path) : Serializer<CachedExternalResource> {
    @Throws(Exception::class)
    override fun read(decoder: Decoder): CachedExternalResource {
        var cachedFile: File? = null
        if (decoder.readBoolean()) {
            cachedFile = denormalizeAndResolveFilePath(decoder.readString())
        }
        val cachedAt = decoder.readLong()
        var metaData: ExternalResourceMetaData? = null
        if (decoder.readBoolean()) {
            val uri = URI(decoder.readString())
            var lastModified: Long = 0
            if (decoder.readBoolean()) {
                lastModified = decoder.readLong()
            }
            val contentType = decoder.readNullableString()
            val contentLength = decoder.readSmallLong()
            val etag = decoder.readNullableString()
            var sha1: HashCode? = null
            if (decoder.readBoolean()) {
                sha1 = HashCode.fromString(decoder.readString())
            }
            metaData = DefaultExternalResourceMetaData(uri, lastModified, contentLength, contentType, etag, sha1)
        }
        return DefaultCachedExternalResource(cachedFile, cachedAt, metaData)
    }

    @Throws(Exception::class)
    override fun write(encoder: Encoder, value: CachedExternalResource) {
        encoder.writeBoolean(value.cachedFile != null)
        if (value.cachedFile != null) {
            encoder.writeString(relativizeAndNormalizeFilePath(value.cachedFile))
        }
        encoder.writeLong(value.cachedAt)
        val metaData = value.externalResourceMetaData
        encoder.writeBoolean(metaData != null)
        if (metaData != null) {
            encoder.writeString(metaData.location.toASCIIString())
            encoder.writeBoolean(metaData.lastModified != null)
            metaData.lastModified?.time?.let(encoder::writeLong)
            encoder.writeNullableString(metaData.contentType)
            encoder.writeSmallLong(metaData.contentLength)
            encoder.writeNullableString(metaData.etag)
            encoder.writeBoolean(metaData.sha1 != null)
            if (metaData.sha1 != null) {
                encoder.writeString(metaData.sha1.toString())
            }
        }
    }

    private fun relativizeAndNormalizeFilePath(cachedFile: File?): String {
        val filePath = cachedFile!!.toPath()
        assert(filePath.startsWith(commonRootPath)) { "Attempting to cache file $filePath not in $commonRootPath" }
        val systemDependentPath = commonRootPath.relativize(filePath).toString()
        return if (filePath.fileSystem.separator != "/") {
            systemDependentPath.replace(filePath.fileSystem.separator, "/")
        } else systemDependentPath
    }

    @Throws(IOException::class)
    private fun denormalizeAndResolveFilePath(relativePath: String): File {
        var relativePath = relativePath
        if (commonRootPath.fileSystem.separator != "/") {
            relativePath = relativePath.replace("/", commonRootPath.fileSystem.separator)
        }
        return commonRootPath.resolve(relativePath).toFile()
    }
}
