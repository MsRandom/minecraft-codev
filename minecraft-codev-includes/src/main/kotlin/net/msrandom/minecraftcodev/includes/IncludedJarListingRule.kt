package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.FileListingRules
import net.msrandom.minecraftcodev.core.utils.serviceLoader

interface IncludedJarListingRule : FileListingRules

val includedJarListingRules = serviceLoader<IncludedJarListingRule>()
