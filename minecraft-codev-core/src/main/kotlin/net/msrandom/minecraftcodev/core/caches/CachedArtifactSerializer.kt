package net.msrandom.minecraftcodev.core.caches

import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.CachedArtifact
import org.gradle.api.internal.artifacts.ivyservice.modulecache.artifacts.DefaultCachedArtifact
import org.gradle.internal.hash.HashCode
import org.gradle.internal.serialize.Decoder
import org.gradle.internal.serialize.Encoder
import org.gradle.internal.serialize.Serializer
import java.io.File
import java.nio.file.Path

data class CachedArtifactSerializer(private val commonRootPath: Path) : Serializer<CachedArtifact> {
    override fun write(
        encoder: Encoder,
        value: CachedArtifact,
    ) {
        encoder.writeBoolean(value.isMissing)
        encoder.writeLong(value.cachedAt)
        val hash = value.descriptorHash.toByteArray()
        encoder.writeBinary(hash)
        if (!value.isMissing) {
            encoder.writeString(relativizeAndNormalizeFilePath(value.cachedFile!!))
        } else {
            encoder.writeSmallInt(value.attemptedLocations().size)
            for (location in value.attemptedLocations()) {
                encoder.writeString(location)
            }
        }
    }

    override fun read(decoder: Decoder): CachedArtifact {
        val isMissing = decoder.readBoolean()
        val createTimestamp = decoder.readLong()
        val encodedHash = decoder.readBinary()
        val hash = HashCode.fromBytes(encodedHash)

        return if (!isMissing) {
            DefaultCachedArtifact(denormalizeAndResolveFilePath(decoder.readString()), createTimestamp, hash)
        } else {
            val size = decoder.readSmallInt()
            val attempted: MutableList<String> = ArrayList(size)
            for (i in 0 until size) {
                attempted.add(decoder.readString())
            }
            DefaultCachedArtifact(attempted, createTimestamp, hash)
        }
    }

    private fun relativizeAndNormalizeFilePath(cachedFile: File): String {
        val filePath = cachedFile.toPath()
        assert(filePath.startsWith(commonRootPath)) { "Attempting to cache file $filePath not in $commonRootPath" }

        val systemDependentPath = commonRootPath.relativize(filePath).toString()
        return if (filePath.fileSystem.separator != "/") {
            systemDependentPath.replace(filePath.fileSystem.separator, "/")
        } else {
            systemDependentPath
        }
    }

    private fun denormalizeAndResolveFilePath(relativePath: String): File {
        val systemDependantPath =
            if (commonRootPath.fileSystem.separator != "/") {
                relativePath.replace("/", commonRootPath.fileSystem.separator)
            } else {
                relativePath
            }

        return commonRootPath.resolve(systemDependantPath).toFile()
    }
}
