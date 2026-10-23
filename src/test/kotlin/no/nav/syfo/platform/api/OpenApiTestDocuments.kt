package no.nav.syfo.platform.api

import org.yaml.snakeyaml.Yaml

internal const val PUBLIC_OPENAPI = "openapi/documentation.yaml"
internal const val INTERNAL_OPENAPI = "openapi/internal-documentation.yaml"
internal const val INTERNAL_LINEMANAGER_SEARCH_OPENAPI = "openapi/internal-linemanager-search.yaml"

/** Loads an OpenAPI document from the test classpath and fails clearly if it is missing or empty. */
internal fun openApiDocument(resource: String): Map<String, Any> {
    val yamlText = OpenApiResources::class.java.classLoader.getResource(resource)
        ?.readText()
        ?: error("Missing OpenAPI document on classpath: $resource")
    return Yaml().load<Map<String, Any>?>(yamlText)
        ?: error("OpenAPI document is empty: $resource")
}

internal fun openApiSchemas(resource: String): Map<*, *> {
    val components = openApiDocument(resource)["components"] as? Map<*, *>
        ?: error("OpenAPI document has no components: $resource")
    return components["schemas"] as? Map<*, *>
        ?: error("OpenAPI document has no component schemas: $resource")
}

private object OpenApiResources
