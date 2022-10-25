package net.msrandom.minecraftcodev.forge

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class RemappedMinecraftTests {
    @Test
    fun `Test remapped Minecraft`() {
        GradleRunner.create()
            .withProjectDir(File("basic-remapped-test"))
            .withPluginClasspath()
            .withArguments("jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
