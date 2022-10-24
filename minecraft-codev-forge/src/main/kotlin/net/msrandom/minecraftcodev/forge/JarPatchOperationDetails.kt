package net.msrandom.minecraftcodev.repository

import java.nio.file.Path

interface JarPatchOperationDetails {
    val input: Path
    val output: Path
    val patches: Path
}

fun JarPatchOperationDetails(input: Path, output: Path, patches: Path) = object : JarPatchOperationDetails {
    override val input get() = input
    override val output get() = output
    override val patches get() = patches

    override fun toString() =
        "${JarPatchOperationDetails::class.simpleName}{${::input.name}=$input, ${::output.name}=$output, ${::patches.name}=$patches, }"
}
