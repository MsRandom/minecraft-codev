package net.msrandom.minecraftcodev.mixins

import net.msrandom.minecraftcodev.core.listedFileRuleList
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

open class MixinsExtension @Inject constructor(objectFactory: ObjectFactory) {
    val rules = objectFactory.listedFileRuleList<String>()
}
