package net.msrandom.minecraftcodev.intersection

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.platform.commons.annotation.Testable
import java.io.File
import java.nio.file.Paths

@Testable
class IntersectionTests {
    @Test
    fun `Test Jar Intersections`() {
        val jarsDirectory = javaClass.getResource("/jars")?.toURI() ?: error("Missing files required for test (/jars)")
        val jarsPath = Paths.get(jarsDirectory)

/*        val intersectionJar =
            JarIntersection.intersection(
                jarsPath.resolve("common-1.16.5-named.jar"),
                jarsPath.resolve("forge-1.16.5-36.2.39-named.jar"),
            )

        intersectionJar.copyTo(Path("/media/sdb1/Projects/Kotlin/minecraft-codev/minecraft-codev-intersections/intersection.jar"))*/
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
