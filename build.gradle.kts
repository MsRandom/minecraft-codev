plugins {
    java
    kotlin("jvm") version "1.6.21"
    kotlin("plugin.serialization") version "1.6.21" apply false
    `java-gradle-plugin`
    `maven-publish`
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(8))
        withSourcesJar()
        withJavadocJar()
    }
}

childProjects.values.forEach { project ->
    with(project) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
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
                val args = mutableListOf(
                    "-opt-in=kotlin.ExperimentalStdlibApi",
                    "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
                    "-Xunrestricted-builder-inference"
                )

                if (LifecycleBasePlugin.BUILD_TASK_NAME in gradle.startParameter.taskNames) {
                    args.addAll(
                        listOf(
                            "-Xno-param-assertions=true",
                            "-Xno-receiver-assertions=true",
                            "-Xno-param-assertions=true"
                        )
                    )
                }

                freeCompilerArgs = args
                apiVersion = "1.6"
                languageVersion = "1.6"
            }
        }

        tasks.test {
            useJUnitPlatform()

            testLogging {
                error {
                    showStandardStreams = true
                }
            }
        }
    }
}
