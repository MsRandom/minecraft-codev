package net.msrandom.minecraftcodev.fabric.runs

import net.msrandom.minecraftcodev.runs.DatagenRunConfigurationData
import net.msrandom.minecraftcodev.runs.MinecraftRunConfiguration
import net.msrandom.minecraftcodev.runs.RunConfigurationDefaultsContainer
import org.gradle.api.Action

open class FabricRunsDefaultsContainer(private val defaults: RunConfigurationDefaultsContainer) {
    fun client() {
        defaults.builder.mainClass(KNOT_CLIENT)
    }

    fun server() {
        defaults.builder.apply {
            arguments("nogui")
            mainClass(KNOT_SERVER)
        }
    }

    fun data(action: Action<FabricDatagenRunConfigurationData>) {
        client()

        defaults.builder.action {
            val data = project.objects.newInstance(FabricDatagenRunConfigurationData::class.java)

            action.execute(data)

            jvmArguments.add(MinecraftRunConfiguration.Argument("-Dfabric-api.datagen"))
            jvmArguments.add(data.getOutputDirectory(this).map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.output-dir=", it) })
            jvmArguments.add(data.modId.map { MinecraftRunConfiguration.Argument("-Dfabric-api.datagen.modid=", it) })
        }
    }

    private fun gameTest(client: Boolean) {
        if (client) {
            client()
        } else {
            server()
        }

        defaults.builder.apply {
            jvmArguments(
                "-Dfabric-api.gametest",
                "-Dfabric.autoTest"
            )
        }
    }

    fun gameTestServer() = gameTest(false)
    fun gameTestClient() = gameTest(true)

    private companion object {
        private const val KNOT_SERVER = "net.fabricmc.loader.launch.knot.KnotServer"
        private const val KNOT_CLIENT = "net.fabricmc.loader.launch.knot.KnotClient"
    }
}

abstract class FabricDatagenRunConfigurationData : DatagenRunConfigurationData
