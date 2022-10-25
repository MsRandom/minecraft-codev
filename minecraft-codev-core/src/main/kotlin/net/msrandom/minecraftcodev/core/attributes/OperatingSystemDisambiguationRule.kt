package net.msrandom.minecraftcodev.core.attributes

import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

class OperatingSystemDisambiguationRule : AttributeDisambiguationRule<OperatingSystemFamily?> {
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun execute(details: MultipleCandidatesDetails<OperatingSystemFamily?>) {
        val consumerValue = details.consumerValue?.name

        if (consumerValue == null && null in details.candidateValues) {
            return details.closestMatch(null)
        }

        val effectiveConsumer = consumerValue ?: DefaultNativePlatform.host().operatingSystem.toFamilyName()

        val bestMatch = details.candidateValues.firstOrNull { it != null && it.name == effectiveConsumer }
        if (bestMatch != null) {
            return details.closestMatch(bestMatch)
        } else {
            if (null in details.candidateValues) {
                return details.closestMatch(null)
            }
        }
    }
}
