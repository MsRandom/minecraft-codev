package net.msrandom.minecraftcodev.forge

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependencyImpl
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependency

open class PatchedMinecraftCodevExtension {
    @JvmOverloads
    operator fun invoke(version: String? = null, patchesConfiguration: String? = null) =
        PatchedMinecraftDependency(MinecraftDependencyImpl("forge", version.orEmpty(), null), patchesConfiguration)

    @JvmOverloads
    fun call(version: String? = null, patchesConfiguration: String? = null) = invoke(version, patchesConfiguration)
}
