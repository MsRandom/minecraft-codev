plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevIncludes") {
        id = project.name
        description = "A Minecraft Codev module that provides ways of including Jar in Jars."
        implementationClass = "net.msrandom.minecraftcodev.includes.MinecraftCodevIncludesPlugin"
    }
}

dependencies {
    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
