package net.msrandom.minecraftcodev.repository

import java.nio.file.Path

interface JarMergeOperationDetails {
    val clientJar: Path
    val serverJar: Path
    val mergedJar: Path
}

fun JarMergeOperationDetails(clientJar: Path, serverJar: Path, mergedJar: Path) = object : JarMergeOperationDetails {
    override val clientJar get() = clientJar
    override val serverJar get() = serverJar
    override val mergedJar get() = mergedJar

    override fun toString() =
        "${JarMergeOperationDetails::class.simpleName}{${::clientJar.name}=$clientJar, ${::serverJar.name}=$serverJar, ${::mergedJar.name}=$mergedJar, }"
}
