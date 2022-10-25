package net.msrandom.minecraftcodev.core.resolve

import java.nio.file.Path

interface ServerJarExtractionOperationDetails {
    val inputJar: Path
    val outputJar: Path
}

fun ServerJarExtractionOperationDetails(inputJar: Path, outputJar: Path) = object : ServerJarExtractionOperationDetails {
    override val inputJar get() = inputJar
    override val outputJar get() = outputJar

    override fun toString() =
        "${ServerJarExtractionOperationDetails::class.simpleName}{${::inputJar.name}=$inputJar, ${::outputJar.name}=$outputJar, }"
}
