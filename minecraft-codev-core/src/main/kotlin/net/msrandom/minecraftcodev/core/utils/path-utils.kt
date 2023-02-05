package net.msrandom.minecraftcodev.core.utils

import java.net.URI
import java.nio.file.*
import kotlin.io.path.copyTo
import kotlin.streams.asSequence

fun Path.createDeterministicCopy(prefix: String, suffix: String): Path {
    val path = Files.createTempFile(prefix, suffix)
    copyTo(path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

    return path
}

fun zipFileSystem(file: Path, create: Boolean = false): FileSystem {
    val uri = URI.create("jar:${file.toUri()}")

    return if (create) {
        FileSystems.newFileSystem(uri, mapOf("create" to true.toString()))
    } else {
        try {
            val delegate = FileSystems.getFileSystem(uri)

            object : FileSystem() {
                override fun close() = Unit
                override fun provider() = delegate.provider()
                override fun isOpen() = delegate.isOpen
                override fun isReadOnly() = delegate.isReadOnly
                override fun getSeparator() = delegate.separator
                override fun getRootDirectories() = delegate.rootDirectories
                override fun getFileStores() = delegate.fileStores
                override fun supportedFileAttributeViews() = delegate.supportedFileAttributeViews()
                override fun getPath(first: String, vararg more: String?) = delegate.getPath(first, *more)
                override fun getPathMatcher(syntaxAndPattern: String?) = delegate.getPathMatcher(syntaxAndPattern)
                override fun getUserPrincipalLookupService() = delegate.userPrincipalLookupService
                override fun newWatchService() = delegate.newWatchService()

                override fun equals(other: Any?) = delegate == other
                override fun hashCode() = delegate.hashCode()
            }
        } catch (exception: FileSystemNotFoundException) {
            FileSystems.newFileSystem(uri, emptyMap<String, Any>())
        }
    }
}

fun <T> Path.walk(action: Sequence<Path>.() -> T) = Files.walk(this).use {
    it.asSequence().action()
}
