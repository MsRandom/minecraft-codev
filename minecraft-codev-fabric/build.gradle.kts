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
    implementation(projects.minecraftCodevCore)

    compileOnly(projects.minecraftCodevAccessWidener)
    compileOnly(projects.minecraftCodevRemapper)
    compileOnly(projects.minecraftCodevMixins)
    compileOnly(projects.minecraftCodevIncludes)
    compileOnly(projects.minecraftCodevRuns)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
