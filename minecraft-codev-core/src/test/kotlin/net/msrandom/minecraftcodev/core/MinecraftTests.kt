package net.msrandom.minecraftcodev.core

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class MinecraftTests {
    @Test
    fun `Basic Minecraft Build`() {
        GradleRunner.create()
            .withProjectDir(File("basic-minecraft-test"))
            .withPluginClasspath()
            .withArguments(
                "commonUniqueSnapshotJar",
                "commonLatestReleaseJar",
                "commonLatestSnapshotJar",
                "commonChangingSnapshotJar",
                "commonSubversionJar",
                "commonClosedMavenRangeJar",
                "commonOpenMavenRangeJar",
                "clientUniqueSnapshotJar",
                "clientSubversionJar",
                "printCompileClasspath",
                "-s"
            )
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
