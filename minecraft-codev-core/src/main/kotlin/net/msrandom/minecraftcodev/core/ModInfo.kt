package net.msrandom.minecraftcodev.core

enum class ModInfoType {
    Platform,
    Version,
    Namespace
}

data class ModInfo(val type: ModInfoType, val info: String, val score: Int)

/**
 * A structure containing all detected info
 */
data class DetectedModInfo(val platforms: List<String>, val versions: List<String>, val namespaces: List<String>)
