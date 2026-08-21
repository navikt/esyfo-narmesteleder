package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerCollection
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerRead
import no.nav.syfo.narmesteleder.domain.Name
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class OpenApiEmployeeLinemanagerSchemaTest :
    StringSpec({
        fun schemas(): Map<*, *> {
            val yamlText = this::class.java.classLoader
                .getResource("openapi/internal-documentation.yaml")
                ?.readText()
                ?: error("Missing internal OpenAPI documentation")
            val root = Yaml().load<Map<String, Any>>(yamlText)
            return (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
        }

        "openapi EmployeeLinemanagerCollection schema matches domain properties" {
            val collectionSchema = schemas()["EmployeeLinemanagerCollection"] as Map<*, *>
            val properties = (collectionSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
            val domainProperties = EmployeeLinemanagerCollection::class.memberProperties.map { it.name }.toSet()

            properties shouldBe domainProperties
            (collectionSchema["required"] as List<*>).map { it as String }.toSet() shouldBe domainProperties
        }

        "openapi EmployeeLinemanagerRead schema matches domain properties" {
            val readSchema = schemas()["EmployeeLinemanagerRead"] as Map<*, *>
            val properties = (readSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
            val domainProperties = EmployeeLinemanagerRead::class.memberProperties.map { it.name }.toSet()

            properties shouldBe domainProperties
            (readSchema["required"] as List<*>).map { it as String }.toSet() shouldBe domainProperties
        }

        "openapi Name schema matches domain properties" {
            val nameSchema = schemas()["Name"] as Map<*, *>
            val properties = (nameSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
            val domainProperties = Name::class.memberProperties.map { it.name }.toSet()

            properties shouldBe domainProperties
            (nameSchema["required"] as List<*>).map { it as String }.toSet() shouldBe domainProperties
        }

        "openapi EmployeeLinemanagerRead schema does not expose national identification number" {
            val readSchema = schemas()["EmployeeLinemanagerRead"] as Map<*, *>
            val properties = (readSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            properties shouldNotContain "nationalIdentificationNumber"
        }
    })
