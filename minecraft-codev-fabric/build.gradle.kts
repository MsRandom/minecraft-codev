plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevFabric") {
        id = project.name
        description = "A Minecraft Codev module that provides helpful utilities for using Minecraft Codev with the Fabric mod loader."
        implementationClass = "net.msrandom.minecraftcodev.fabric.MinecraftCodevFabricPlugin"
    }
}

dependencies {
    implementation(projects.minecraftCodevAccessWidener)
    implementation(projects.minecraftCodevRemapper)
    implementation(projects.minecraftCodevMixins)
    implementation(projects.minecraftCodevIncludes)
    implementation(projects.minecraftCodevRuns)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
