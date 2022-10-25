package org.gradle.kotlin.dsl

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.forge.MinecraftCodevForgePlugin
import net.msrandom.minecraftcodev.forge.dependency.PatchedMinecraftDependency

val MinecraftDependency.patched get() = patched()

@JvmOverloads
fun MinecraftDependency.patched(patches: String? = null) = PatchedMinecraftDependency(this, patches)
