plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevRuns") {
        id = project.name
        description = "A Minecraft Codev module that provides ways of running Minecraft in a development environment."
        implementationClass = "net.msrandom.minecraftcodev.runs.MinecraftCodevRunsPlugin"
    }
}

dependencies {
    implementation(group = "org.apache.commons", name = "commons-lang3", version = "3.12.0")

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
