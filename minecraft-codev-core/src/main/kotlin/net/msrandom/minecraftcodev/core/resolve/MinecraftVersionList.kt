package net.msrandom.minecraftcodev.core.resolve

import java.text.SimpleDateFormat
import java.util.*

class MinecraftVersionList(manifest: MinecraftVersionManifest) {
    val versions = manifest.versions.associateBy(MinecraftVersionManifest.VersionInfo::id)
    val latest = manifest.latest.mapValues { (_, value) -> versions.getValue(value) }

    init {

    }

    val snapshotTimestamps = manifest.versions.associateBy {
        val time = it.releaseTime
        val date = Date.from(time.toInstant())

        SimpleDateFormat("yyyyMMdd.HHmmss-1").format(date)
        time.year.toString() + time.monthValue.toString().padStart(2, '0') + time.dayOfMonth.toString().padStart(2, '0')
    }
}
