package net.msrandom.minecraftcodev.core

sealed interface ModInfo {
    /**
     * A score of how accurate this mod info may be.
     * Used to select best matches.
     */
    val score: Int
}

/**
 * The detected platform for this mod, such as forge, fabric, quilt or vanilla, etc
 */
data class ModPlatformInfo(val platform: String, override val score: Int) : ModInfo

/**
 * The detected mapping namespace for this mod, used for remapping if possible
 */
data class ModMappingNamespaceInfo(val namespace: String, override val score: Int) : ModInfo

/**
 * A structure containing all detected info
 */
data class DetectedModInfo(val platforms: List<String>, val namespaces: List<String>)
