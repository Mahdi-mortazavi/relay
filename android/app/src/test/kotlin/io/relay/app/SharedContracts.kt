package io.relay.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Locates the repo-level /shared directory from the unit-test working
 * directory (module dir in Gradle), so tests always run against the single
 * source of truth instead of a copied fixture.
 */
object SharedContracts {
    val dir: File by lazy {
        var current: File? = File(System.getProperty("user.dir")).absoluteFile
        while (current != null) {
            val candidate = File(current, "shared/test-vectors.json")
            if (candidate.isFile) return@lazy File(current, "shared")
            current = current.parentFile
        }
        error("Could not locate /shared above ${System.getProperty("user.dir")}")
    }

    fun json(name: String): JsonElement =
        Json.parseToJsonElement(File(dir, name).readText(Charsets.UTF_8))
}
