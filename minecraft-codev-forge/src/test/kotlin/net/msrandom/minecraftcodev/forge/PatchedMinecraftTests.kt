package net.msrandom.minecraftcodev.forge

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class PatchedMinecraftTests {
    private fun test(pathName: String) {
        GradleRunner.create()
            .withProjectDir(File(pathName))
            .withPluginClasspath()
            .withArguments("jar", "printCompileClasspath", "-s")
            .forwardOutput()
            .withDebug(true)
            .build()
    }

    @Test fun `Test patched Minecraft`() = test("basic-patched-test")

    @Test fun `Test remapped patched Minecraft`() = test("remapped-patched-test")
}
