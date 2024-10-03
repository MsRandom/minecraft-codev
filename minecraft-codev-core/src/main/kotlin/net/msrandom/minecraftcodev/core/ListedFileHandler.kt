package net.msrandom.minecraftcodev.core

import java.nio.file.Path

interface ListedFileHandler {
    /**
     * List all the included jars
     * @param root New root that matches the format for the root this handler was loaded with
     */
    fun list(root: Path): List<String>

    /**
     * Remove included Jar listing & the actual files
     * @param root New root that matches the format for the root this handler was loaded with
     */
    fun remove(root: Path)
}

fun interface FileListingRules {
    /**
     * Load a handler for the mod in directory
     * @param directory The directory which the mod is in, can potentially be in a zip file system
     * @return A [ListedFileHandler] to list the mixin configs or remove them, or null if this rule doesn't apply
     */
    fun load(directory: Path): ListedFileHandler?
}
