plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevMixins") {
        id = project.name
        description = "A Minecraft Codev module that allows applying mixins to Minecraft dependencies and injecting mixins into run configurations."
        implementationClass = "net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin"
    }
}

dependencies {
    implementation(group = "org.spongepowered", name = "mixin", version = "0.8.5")

    compileOnly(projects.minecraftCodevGradleLinkage)

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
