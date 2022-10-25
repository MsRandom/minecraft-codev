enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "minecraft-codev"

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenCentral()
        maven(url = "https://maven.fabricmc.net") // Used for mapping
        maven(url = "https://maven.quiltmc.org/repository/release/") // Used for decompilation
        maven(url = "https://maven.minecraftforge.net") // Used for version sorting
    }
}

include("minecraft-codev-gradle-linkage")
include("minecraft-codev-core", "minecraft-codev-core:side-annotations")
include("minecraft-codev-remapper")
include("minecraft-codev-forge", "minecraft-codev-forge:forge-runtime")
include("minecraft-codev-fabric")
include("minecraft-codev-runs")
include("minecraft-codev-access-widener")
include("minecraft-codev-mixin")
