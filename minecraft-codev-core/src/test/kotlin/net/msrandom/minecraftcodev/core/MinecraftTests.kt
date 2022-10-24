package net.msrandom.minecraftcodev.core

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class MinecraftTests {
    @Test
    fun `Test basic Minecraft`() {
        GradleRunner.create()
            .withProjectDir(File("basic-minecraft-test"))
            .withPluginClasspath()
            .withArguments("common12Jar", "common16Jar", "common18Jar", "client12Jar", "client16Jar", "client18Jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
