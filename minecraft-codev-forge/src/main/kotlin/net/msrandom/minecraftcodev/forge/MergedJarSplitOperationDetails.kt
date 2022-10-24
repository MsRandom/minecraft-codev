package net.msrandom.minecraftcodev.forge

import java.nio.file.Path

interface MergedJarSplitOperationDetails {
    val mergedInput: Path
    val commonOutput: Path
    val clientOutput: Path
}

fun MergedJarSplitOperationDetails(mergedInput: Path, commonOutput: Path, clientOutput: Path) = object : MergedJarSplitOperationDetails {
    override val mergedInput get() = mergedInput
    override val commonOutput get() = commonOutput
    override val clientOutput get() = clientOutput

    override fun toString() =
        "${MergedJarSplitOperationDetails::class.simpleName}{${::mergedInput.name}=$mergedInput, ${::commonOutput.name}=$commonOutput, ${::clientOutput.name}=${clientOutput} }"
}

