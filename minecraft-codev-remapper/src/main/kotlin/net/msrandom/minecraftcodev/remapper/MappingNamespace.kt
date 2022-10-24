package net.msrandom.minecraftcodev.remapper

object MappingNamespace {
    /**
     * Implies that this Jar uses the proguard mappings included in the vanilla Jars shipped.
     */
    const val OBF = "obf"

    /**
     * Implies that this Jar was mapped to Fabric/Quilt's intermediary format.
     */
    const val INTERMEDIARY = "intermediary"

    /**
     * Implies that this Jar is correctly mapped to this project's mappings.
     */
    const val NAMED = "named"
}
