package net.msrandom.minecraftcodev.core.resolve

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MinecraftVersionList(private val manifest: MinecraftVersionManifest) {
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

    private val latestReleaseId = (URL("https://maven.msrandom.net/repository/root/minecraft-latest-version.txt").openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", "minecraft-codev")
        connect()
        inputStream.reader()
    }.use {
        it.readText().trim()
    }

    fun snapshot(base: String) = if (base == latestReleaseId) {
        manifest.versions.first().id
    } else {
        versions[base]?.id
    }
}
