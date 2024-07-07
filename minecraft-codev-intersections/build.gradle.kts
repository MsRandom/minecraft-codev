plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevIntersections") {
        id = project.name
        description = "A Minecraft Codev module that provides ways to get the common ABI of two different Jars, which can be useful when making a common Jar between multiple versions."
        implementationClass = "net.msrandom.minecraftcodev.intersection.MinecraftCodevIntersectionPlugin"
    }
}

dependencies {
    implementation(projects.minecraftCodevCore)
    implementation(projects.minecraftCodevRemapper)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
