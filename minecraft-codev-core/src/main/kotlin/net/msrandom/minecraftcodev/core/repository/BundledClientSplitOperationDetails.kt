package net.msrandom.minecraftcodev.core.repository

import java.nio.file.Path

interface BundledClientSplitOperationDetails {
    val clientInput: Path
    val serverInput: Path
    val clientOutput: Path
}

fun BundledClientSplitOperationDetails(clientInput: Path, serverInput: Path, clientOutput: Path) = object : BundledClientSplitOperationDetails {
    override val clientInput get() = clientInput
    override val serverInput get() = serverInput
    override val clientOutput get() = clientOutput

    override fun toString() = "${BundledClientSplitOperationDetails::class.simpleName}" +
            "{${::clientInput.name}=$clientInput, " +
            "${::serverInput.name}=$serverInput, " +
            "${::clientOutput.name}=${clientOutput} }"
}
