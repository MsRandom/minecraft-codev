package net.msrandom.minecraftcodev.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetsIndex(
    @SerialName("map_to_resources") val mapToResources: Boolean = false,
    val objects: Map<String, AssetObject>
) {
    @Serializable
    data class AssetObject(val hash: String, val size: Long)
}
