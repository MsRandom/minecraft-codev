package net.msrandom.minecraftcodev.core.attributes

import net.msrandom.minecraftcodev.core.utils.osVersion
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.CompatibilityCheckDetails

class VersionPatternCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        val consumerValue = details.consumerValue

        when {
            consumerValue == null -> details.compatible()
            (details.producerValue ?: osVersion()) matches Regex(consumerValue) -> details.compatible()
            else -> details.incompatible()
        }
    }
}
