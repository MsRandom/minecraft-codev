package net.msrandom.minecraftcodev.forge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromStream
import net.msrandom.minecraftcodev.core.MinecraftCodevPlugin.Companion.json
import net.msrandom.minecraftcodev.core.ModuleLibraryIdentifier
import net.msrandom.minecraftcodev.core.utils.zipFileSystem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Serializable
data class UserdevConfig(
    val notchObf: Boolean = false,
    val universalFilters: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val mcp: String,
    val ats: List<String>,
    val binpatches: String,
    val binpatcher: PatchLibrary,
    val sources: String,
    val universal: String,
    val libraries: List<ModuleLibraryIdentifier>,
    val inject: String? = null,
    val runs: Runs,
    val spec: Int
) {
    @Serializable
    data class Runs(
        val server: Run,
        val data: Run? = null,
        val client: Run,
        val gameTestServer: Run? = null
    )

    @Serializable
    data class Run(
        val main: String,
        val args: List<String> = emptyList(),
        val jvmArgs: List<String> = emptyList(),
        val client: Boolean? = null,
        val env: Map<String, String>,
        val props: Map<String, String> = emptyMap()
    )

    companion object {
        private val cache = ConcurrentHashMap<File, UserdevConfig?>()

        fun fromFile(file: File) = cache.computeIfAbsent(file) {
            zipFileSystem(it.toPath()).use { fs ->
                val path = fs.getPath("config.json")
                if (path.exists()) {
                    path.inputStream().use(json::decodeFromStream)
                } else {
                    null
                }
            }
        }
    }
}
