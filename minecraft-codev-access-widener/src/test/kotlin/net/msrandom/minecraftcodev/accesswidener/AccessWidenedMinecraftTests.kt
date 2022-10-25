package net.msrandom.minecraftcodev.accesswidener

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class AccessWidenedMinecraftTests {
    @Test
    fun `Test access widened Minecraft`() {
        GradleRunner.create()
            .withProjectDir(File("basic-access-widened-test"))
            .withPluginClasspath()
            .withArguments("jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
