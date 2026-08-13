package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class OpenApiLinemanagerStatisticsSchemaTest :
    StringSpec({
        "openapi LinemanagerStatistics schema matches domain properties" {
            val yamlText = this::class.java.classLoader
                .getResource("openapi/internal-documentation.yaml")!!
                .readText()
            val root = Yaml().load<Map<String, Any>>(yamlText)
            val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
            val statisticsSchema = schemas["LinemanagerStatistics"] as Map<*, *>
            val properties = (statisticsSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
            val domainProperties = LinemanagerStatistics::class.memberProperties.map { it.name }.toSet()

            properties shouldBe domainProperties
            (statisticsSchema["required"] as List<*>).map { it as String }.toSet() shouldBe domainProperties
        }
    })
