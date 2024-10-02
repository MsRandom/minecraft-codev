package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.FileListingRules
import java.util.ServiceLoader

interface MixinListingRule : FileListingRules<String>

val mixinListingRules = ServiceLoader.load(MixinListingRule::class.java).toList()
