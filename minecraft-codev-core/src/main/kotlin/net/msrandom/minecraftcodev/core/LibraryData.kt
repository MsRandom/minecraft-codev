package net.msrandom.minecraftcodev.core

import com.google.common.collect.Multimap

class LibraryData(
    /**
     * Libraries required on the server, list of dependency notations.
     */
    val common: Collection<ModuleLibraryIdentifier>,

    /**
     * For runtime libraries specific to the client(this should not include anything in [common]).
     * Indexed by the OS they're needed on.
     */
    val client: Multimap<MinecraftVersionMetadata.Rule.OperatingSystem, ModuleLibraryIdentifier>,
)
