package no.nav.syfo.narmestelederstatistikk.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class LinemanagerStatisticsOpenApiSchemaTest :
    FunSpec({
        test("OpenAPI LinemanagerStatistics schema matches response properties and required fields") {
            val schema = statisticsSchemas()["LinemanagerStatistics"] as Map<*, *>
            val properties = (schema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
            val responseProperties = LinemanagerStatisticsResponse::class.memberProperties.map { it.name }.toSet()

            properties shouldBe responseProperties
            (schema["required"] as List<*>).map { it as String }.toSet() shouldBe responseProperties
        }
    })

private fun statisticsSchemas(): Map<*, *> {
    val yamlText = LinemanagerStatisticsOpenApiSchemaTest::class.java.classLoader
        .getResource("openapi/internal-documentation.yaml")
        ?.readText()
        ?: error("Missing internal OpenAPI documentation")
    val root = Yaml().load<Map<String, Any>>(yamlText)
    return (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
}
