package net.msrandom.minecraftcodev.intersection

import net.msrandom.minecraftcodev.intersection.resolve.JarIntersection
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.div

@Testable
class IntersectionTests {
    @Test
    fun `Test Jar Intersections`() {
        val jarsDirectory = javaClass.getResource("/jars")?.toURI() ?: error("Missing files required for test (/jars)")
        val jarsPath = Paths.get(jarsDirectory)

        JarIntersection.intersection(
            jarsPath.resolve("a.jar"),
            jarsPath.resolve("b.jar"),
        )
    }

    @Test
    fun `Test Intersection of 3 Versions`() {
        GradleRunner.create()
            .withProjectDir(File("intersection-test"))
            .withPluginClasspath()
            .withArguments(
                "jar",
                "-s",
            )
            .forwardOutput()
            .withDebug(true)
            .build()
    }
}
