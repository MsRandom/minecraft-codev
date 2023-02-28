package net.msrandom.minecraftcodev.core.resolve

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class MinecraftVersionList(private val manifest: MinecraftVersionManifest, private val root: Path) {
    val versions = manifest.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
    val latest = manifest.latest.mapValues { (_, value) -> versions.getValue(value) }

    val snapshotTimestamps = buildMap {
        for (version in manifest.versions.reversed()) {
            var i = 1
            var date: String
            do {
                date = SimpleDateFormat("yyyyMMdd.HHmmss-${i++}").format(Date.from(version.releaseTime.toInstant()))
            } while (date in this)

            put(date, version.id)
        }
    }

    private val latestReleaseId by lazy {
        val path = root.resolve("minecraft-latest-version.txt")

        try {
            (URL("https://maven.msrandom.net/repository/root/minecraft-latest-version.txt").openConnection() as HttpURLConnection).run {
                setRequestProperty("User-Agent", "minecraft-codev")
                connect()
                inputStream.reader()
            }.use {
                val result = it.readText().trim()

                path.writeText(result)
                result
            }
        } catch (error: IOException) {
            if (path.notExists()) {
                throw error;
            }

            path.readText()
        }
    }

    fun snapshot(base: String) = if (base == latestReleaseId) {
        manifest.versions.first().id
    } else {
        versions[base]?.id
    }
}
