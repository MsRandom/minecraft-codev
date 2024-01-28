plugins {
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

tasks.shadowJar {
    relocate("org.gradle.internal.impldep.", "")

    configurations = emptyList()
}

configurations.default {
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
