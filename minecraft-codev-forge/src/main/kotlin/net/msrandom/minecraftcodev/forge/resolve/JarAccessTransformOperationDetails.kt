package net.msrandom.minecraftcodev.forge.resolve

import java.nio.file.Path

interface JarAccessTransformOperationDetails {
    val input: Path
    val output: Path
    val accessTransformers: List<Path>
}

fun JarAccessTransformOperationDetails(input: Path, output: Path, accessTransformers: List<Path>) = object : JarAccessTransformOperationDetails {
    override val input get() = input
    override val output get() = output
    override val accessTransformers get() = accessTransformers

    override fun toString() =
        "${JarAccessTransformOperationDetails::class.simpleName}{${::input.name}=$input, ${::output.name}=$output, ${::accessTransformers.name}=$accessTransformers, }"
}
