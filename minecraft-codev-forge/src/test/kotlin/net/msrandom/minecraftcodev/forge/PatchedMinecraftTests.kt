package net.msrandom.minecraftcodev.forge

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class PatchedMinecraftTests {
    @Test
    fun `Test patched Minecraft`() {
        GradleRunner.create()
            .withProjectDir(File("basic-patched-test"))
            .withPluginClasspath()
            .withArguments("jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }

    @Test
    fun `Test remapped patched Minecraft`() {
        GradleRunner.create()
            .withProjectDir(File("remapped-patched-test"))
            .withPluginClasspath()
            .withArguments("jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
