package net.msrandom.minecraftcodev.repository

import java.nio.file.Path

interface JarInjectOperationDetails {
    val input: Path
    val output: Path
    val userdevConfig: Path
}

fun JarInjectOperationDetails(input: Path, output: Path, userdevConfig: Path) = object : JarInjectOperationDetails {
    override val input get() = input
    override val output get() = output
    override val userdevConfig get() = userdevConfig

    override fun toString() =
        "${JarInjectOperationDetails::class.simpleName}{${::input.name}=$input, ${::output.name}=$output, ${::userdevConfig.name}=$userdevConfig, }"
}
