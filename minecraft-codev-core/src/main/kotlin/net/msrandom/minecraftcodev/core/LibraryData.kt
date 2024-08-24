package net.msrandom.minecraftcodev.core

import com.google.common.collect.Multimap
import net.msrandom.minecraftcodev.core.resolve.MinecraftVersionMetadata

class LibraryData(
    /**
     * Libraries required on the server, list of dependency notations.
     */
    val common: Collection<ModuleLibraryIdentifier>,
    /**
     * For libraries specific to the client(this should not include anything in [common]).
     * Indexed by the OS they're needed on, or null if they don't have special rules.
     */
    val client: Multimap<MinecraftVersionMetadata.Rule.OperatingSystem?, ModuleLibraryIdentifier>,
)
