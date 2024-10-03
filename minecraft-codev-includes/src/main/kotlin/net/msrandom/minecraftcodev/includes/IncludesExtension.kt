package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.FileListingRules
import java.util.ServiceLoader

interface IncludedJarListingRule : FileListingRules

val includedJarListingRules = ServiceLoader.load(IncludedJarListingRule::class.java).toList()
