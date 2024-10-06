package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.FileListingRules
import net.msrandom.minecraftcodev.core.utils.serviceLoader

interface MixinListingRule : FileListingRules

val mixinListingRules = serviceLoader<MixinListingRule>()
