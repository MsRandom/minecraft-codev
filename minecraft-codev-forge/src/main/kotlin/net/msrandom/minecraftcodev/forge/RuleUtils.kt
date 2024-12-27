package net.msrandom.minecraftcodev.forge

import org.gradle.api.capabilities.MutableCapabilitiesMetadata

fun MutableCapabilitiesMetadata.disableVariant() {
    for (capability in capabilities) {
        removeCapability(capability.group, capability.name)
    }

    addCapability("___dummy___", "___dummy___", "___dummy___")
}
