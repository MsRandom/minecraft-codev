package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.Serializable

@Serializable
data class McpConfig(val version: String, val official: Boolean = false, val data: Data, val functions: Map<String, PatchLibrary>) {
    // TODO maybe replace this with a Map<String, String>, it'd allow better argument replacing
    @Serializable
    data class Data(
        val access: String? = null,
        val constructors: String? = null,
        val exceptions: String? = null,
        val mappings: String,
        val statics: String? = null,
    )
}
