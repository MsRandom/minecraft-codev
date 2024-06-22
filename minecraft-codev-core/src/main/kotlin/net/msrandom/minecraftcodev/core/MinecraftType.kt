package net.msrandom.minecraftcodev.core

import net.msrandom.minecraftcodev.core.resolve.MinecraftComponentResolvers

enum class MinecraftType(val module: String) {
    /**
     * Everything that is in both the Minecraft client and server, typically closer to a server Jar. Not available on versions without servers.
     */
    Common(MinecraftComponentResolvers.COMMON_MODULE),

    /**
     * Includes only client only content, depends on [Common]. Available for every Minecraft version.
     */
    Client(MinecraftComponentResolvers.CLIENT_MODULE),

    /**
     * Includes server only official mappings, only available for [1.14.4,) versions.
     * May not be sufficient to fully map [Common] without [ClientMappings] in some versions before 1.17 as [Common] can include some removed client Jar elements.
     */
    ServerMappings("server-mappings"),

    /**
     * Includes server only official mappings, only available for [1.14.4,) versions.
     * Always able to fully map both [Common] and [Client], without parameters.
     */
    ClientMappings("client-mappings"),

    /**
     * Marker module holding a dependency to everything [Client] needs in a special natives directory during runtime for things like java.library.path and org.lwjgl.librarypath.
     */
    ClientNatives(MinecraftComponentResolvers.CLIENT_NATIVES_MODULE),
    ;

    override fun toString() = module
}
