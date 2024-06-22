package net.msrandom.minecraftcodev.core

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File

@Testable
class MinecraftTests {
    private fun getJar() {}

    @Test
    fun `Legacy Server Stripping`() {
    }

    @Test
    fun `Legacy Jar Splitting`() {
    }

    @Test
    fun `Bundled Server Extraction`() {
    }

    @Test
    fun `Bundled Jar Splitting`() {
    }

    @Test
    fun `Basic Minecraft Build`() {
        GradleRunner.create()
            .withProjectDir(File("basic-minecraft-test"))
            .withPluginClasspath()
            .withArguments(
                "commonUniqueSnapshotJar",
                "commonLatestReleaseJar",
                "commonLatestSnapshotJar",
                "commonSubversionJar",
                "commonClosedMavenRangeJar",
                "commonOpenMavenRangeJar",
                "clientUniqueSnapshotJar",
                "clientSubversionJar",
                "-s",
            )
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
