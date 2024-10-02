package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.FileListingRules
import java.util.ServiceLoader

data class IncludedJar(
    val path: String,
    val group: String?,
    val module: String?,
    val version: String?,
)

interface IncludedJarListingRule : FileListingRules<IncludedJar>

val includedJarListingRules = ServiceLoader.load(IncludedJarListingRule::class.java).toList()
