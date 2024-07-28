plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins.create("minecraftCodevMixins") {
        id = project.name
        description = "A Minecraft Codev module that allows applying mixins to Minecraft dependencies " +
            "and injecting mixins into run configurations."
        implementationClass = "net.msrandom.minecraftcodev.mixins.MinecraftCodevMixinsPlugin"
    }
}

dependencies {
    api(group = "net.fabricmc", name = "sponge-mixin", version = "0.15.0+mixin.0.8.7")

    implementation(projects.minecraftCodevCore)
}

tasks.test {
    dependsOn(tasks.pluginUnderTestMetadata)
}
