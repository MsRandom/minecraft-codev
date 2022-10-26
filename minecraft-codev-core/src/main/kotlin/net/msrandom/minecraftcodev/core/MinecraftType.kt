package net.msrandom.minecraftcodev.core

enum class MinecraftType(val module: String) {
    Common("common"),
    Client("client"),
    ServerMappings("server-mappings"),
    ClientMappings("client-mappings");

    override fun toString() = module
}
