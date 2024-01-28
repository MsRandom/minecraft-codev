package net.msrandom.minecraftcodev.includes

import net.msrandom.minecraftcodev.core.listedFileRuleList
import org.gradle.api.model.ObjectFactory

data class IncludedJar(
    val path: String,
    val group: String?,
    val module: String?,
    val version: String?,
)

open class IncludesExtension(objectFactory: ObjectFactory) {
    val rules = objectFactory.listedFileRuleList<IncludedJar>()
}
