package net.msrandom.minecraftcodev.runs

import groovy.transform.CompileStatic
import org.jetbrains.gradle.ext.BeforeRunTask
import javax.inject.Inject

@CompileStatic
class BeforeRunApplication @Inject constructor(name: String) : BeforeRunTask() {
    init {
        type = "runConfigurationTask"
        this.name = name
    }

    override fun toMap() = super.toMap() + mapOf()
}
