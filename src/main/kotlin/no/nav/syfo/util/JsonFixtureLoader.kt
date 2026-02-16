package no.nav.syfo.util

import com.fasterxml.jackson.core.type.TypeReference
import no.nav.syfo.application.kafka.jacksonMapper
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Utility class for loading and parsing JSON fixture files into typed objects.
 * Useful for fake clients in tests and local development.
 *
 * @param basePath The base path for fixture files. Supports:
 *   - Classpath resources: prefix with "classpath:" (e.g., "classpath:fixtures/")
 *   - Filesystem paths: any other path (e.g., "/path/to/fixtures/" or "local-dev-resources/")
 */
class JsonFixtureLoader(private val basePath: String) {

    private val objectMapper = jacksonMapper()

    /**
     * Load and parse a JSON file into the specified type.
     *
     * @param filename The filename relative to basePath
     * @return The parsed object
     * @throws FileNotFoundException if the file does not exist
     * @throws com.fasterxml.jackson.core.JsonParseException if the JSON is malformed
     * @throws com.fasterxml.jackson.databind.JsonMappingException if the JSON cannot be mapped to the type
     */
    inline fun <reified T> load(filename: String): T = load(filename, object : TypeReference<T>() {})

    /**
     * Load and parse a JSON file into the specified type, returning null if the file doesn't exist.
     *
     * @param filename The filename relative to basePath
     * @return The parsed object, or null if the file does not exist
     * @throws com.fasterxml.jackson.core.JsonParseException if the JSON is malformed
     * @throws com.fasterxml.jackson.databind.JsonMappingException if the JSON cannot be mapped to the type
     */
    inline fun <reified T> loadOrNull(filename: String): T? = loadOrNull(filename, object : TypeReference<T>() {})

    /**
     * Check if a fixture file exists.
     *
     * @param filename The filename relative to basePath
     * @return true if the file exists, false otherwise
     */
    fun exists(filename: String): Boolean = getInputStream(filename) != null

    @PublishedApi
    internal fun <T> load(filename: String, typeReference: TypeReference<T>): T {
        val fullPath = resolvePath(filename)
        val inputStream = getInputStream(filename)
            ?: throw FileNotFoundException("Fixture file not found: $fullPath")
        return objectMapper.readValue(inputStream, typeReference)
    }

    @PublishedApi
    internal fun <T> loadOrNull(filename: String, typeReference: TypeReference<T>): T? {
        val inputStream = getInputStream(filename) ?: return null
        return objectMapper.readValue(inputStream, typeReference)
    }

    private fun getInputStream(filename: String): InputStream? {
        val fullPath = resolvePath(filename)
        return if (basePath.startsWith(CLASSPATH_PREFIX)) {
            val resourcePath = fullPath.removePrefix(CLASSPATH_PREFIX)
            javaClass.classLoader.getResourceAsStream(resourcePath)
        } else {
            val file = File(fullPath)
            if (file.exists() && file.isFile) {
                file.inputStream()
            } else {
                null
            }
        }
    }

    private fun resolvePath(filename: String): String {
        val normalizedBase = if (basePath.endsWith("/")) basePath else "$basePath/"
        return "$normalizedBase$filename"
    }

    companion object {
        const val CLASSPATH_PREFIX = "classpath:"
    }
}
