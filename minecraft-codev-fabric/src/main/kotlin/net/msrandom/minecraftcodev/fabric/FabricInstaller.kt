package net.msrandom.minecraftcodev.fabric

import kotlinx.serialization.Serializable

@Serializable
data class FabricInstaller(
    val libraries: FabricLibraries,
    val mainClass: MainClass,
) {
    @Serializable
    data class FabricLibraries(
        val client: List<FabricLibrary>,
        val common: List<FabricLibrary>,
        val server: List<FabricLibrary>,
        val development: List<FabricLibrary>,
    )

    @Serializable
    data class FabricLibrary(val name: String)

    @Serializable
    data class MainClass(
        val client: String,
        val server: String,
    )
}
