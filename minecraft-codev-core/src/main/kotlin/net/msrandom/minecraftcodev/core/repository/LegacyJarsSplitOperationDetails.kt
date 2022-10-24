package net.msrandom.minecraftcodev.core.repository

import java.nio.file.Path

interface LegacyJarsSplitOperationDetails {
    val clientInput: Path
    val serverInput: Path
    val commonOutput: Path
    val clientOutput: Path
}

fun LegacyJarsSplitOperationDetails(clientInput: Path, serverInput: Path, commonOutput: Path, clientOutput: Path) = object : LegacyJarsSplitOperationDetails {
    override val clientInput get() = clientInput
    override val serverInput get() = serverInput
    override val commonOutput get() = commonOutput
    override val clientOutput get() = clientOutput

    override fun toString() = "${LegacyJarsSplitOperationDetails::class.simpleName}" +
            "{${::clientInput.name}=$clientInput, " +
            "${::serverInput.name}=$serverInput, " +
            "${::commonOutput.name}=$commonOutput, " +
            "${::clientOutput.name}=${clientOutput} }"
}
