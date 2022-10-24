package org.gradle.kotlin.dsl

import net.msrandom.minecraftcodev.core.dependency.MinecraftDependency
import net.msrandom.minecraftcodev.forge.PatchedMinecraftDependency
import net.msrandom.minecraftcodev.forge.PatcherExtension

val MinecraftDependency.patched get() = patched()

@JvmOverloads
fun MinecraftDependency.patched(patches: String = PatcherExtension.PATCHES_CONFIGURATION) = PatchedMinecraftDependency(this, patches)
