package net.msrandom.minecraftcodev.repository

import java.nio.file.Path

interface JarRenameOperationDetails {
    val obfJar: Path
    val srgJar: Path
    val mappings: Path
}

fun JarRenameOperationDetails(obfJar: Path, srgJar: Path, mappings: Path) = object : JarRenameOperationDetails {
    override val obfJar get() = obfJar
    override val srgJar get() = srgJar
    override val mappings get() = mappings

    override fun toString() =
        "${JarRenameOperationDetails::class.simpleName}{${::obfJar.name}=$obfJar, ${::srgJar.name}=$srgJar, ${::mappings.name}=$mappings, }"
}
