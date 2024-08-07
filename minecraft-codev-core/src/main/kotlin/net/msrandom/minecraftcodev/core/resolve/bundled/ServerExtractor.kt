package net.msrandom.minecraftcodev.core.resolve.bundled

import java.nio.file.FileSystem
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.copyTo
import kotlin.io.path.readLines

object ServerExtractor {
    fun extract(
        version: String,
        newServer: Path,
        serverFs: FileSystem,
        librariesList: Path,
    ): List<String> {
        val versions =
            serverFs.getPath("META-INF/versions.list").readLines().associate {
                val parts = it.split('\t')
                parts[1] to parts[2]
            }

        serverFs.getPath(
            "META-INF/versions/${versions.getValue(version)}",
        ).copyTo(newServer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)

        return librariesList.readLines().map { it.split('\t')[1] }
    }
}
