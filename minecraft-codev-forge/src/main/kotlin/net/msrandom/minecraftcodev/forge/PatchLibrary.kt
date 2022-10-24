package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.Serializable

@Serializable
data class PatchLibrary(val version: String, val args: List<String>)
