package net.msrandom.minecraftcodev.accesswidener

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty

fun interface AccessWidenerRule {
    fun readAccessWideners()
}

open class AccessWidenerExtension(objectFactory: ObjectFactory) {
    val rules: ListProperty<AccessWidenerRule> = objectFactory.listProperty(AccessWidenerRule::class.java)
}
