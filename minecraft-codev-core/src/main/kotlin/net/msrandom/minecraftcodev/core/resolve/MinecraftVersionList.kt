package net.msrandom.minecraftcodev.core.resolve

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

    fun snapshot(base: String) = versions[base]?.id ?: manifest.versions.firstOrNull()?.id
}
