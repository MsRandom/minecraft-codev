plugins {
    java
    kotlin("jvm") version "1.8.+"
    kotlin("plugin.serialization") version "1.8.+" apply false
    `java-gradle-plugin`
    `maven-publish`
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(11))
        withSourcesJar()
        withJavadocJar()
    }
}

childProjects.values.forEach { project ->
    with(project) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
        apply(plugin = "maven-publish")

        dependencies {
            implementation(gradleApi())
            implementation(kotlin("stdlib"))

            testImplementation(gradleTestKit())
            testImplementation(group = "org.junit.jupiter", name = "junit-jupiter-api", version = "5.9.0-M1")
            testRuntimeOnly(group = "org.junit.jupiter", name = "junit-jupiter-engine")
        }

        publishing {
            repositories {
                mavenLocal()
            }
        }

        tasks.compileKotlin {
            kotlinOptions {
                freeCompilerArgs = listOf(
                    "-opt-in=kotlin.ExperimentalStdlibApi",
                    "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                    "-Xunrestricted-builder-inference"
                )

                apiVersion = "1.6"
                languageVersion = "1.6"
            }
        }

        tasks.test {
            maxHeapSize = "3G"

            useJUnitPlatform()

            testLogging {
                error {
                    showStandardStreams = true
                }
            }
        }
    }
}
