package net.msrandom.minecraftcodev.core

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute

interface MappingsNamespace : Named {
    companion object {
        @JvmStatic
        val attribute: Attribute<MappingsNamespace> = Attribute.of("net.msrandom.minecraftcodev.mappings", MappingsNamespace::class.java)

        /**
         * Implies that this Jar uses the proguard mappings included in the vanilla Jars shipped.
         */
        const val OBF = "obf"
    }
}
