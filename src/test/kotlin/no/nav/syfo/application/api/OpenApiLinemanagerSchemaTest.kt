package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.util.logger
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class OpenApiLinemanagerSchemaTest :
    StringSpec({
        val logger = logger()

        "openapi Linemanager schema matches Linemanager and nested Manager properties" {
            val yamlText = this::class.java.classLoader
                .getResource("openapi/documentation.yaml")!!
                .readText()

            val yaml = Yaml()
            val root = yaml.load<Map<String, Any>>(yamlText)

            val schemas = root["components"] as Map<*, *>
            val schemaMap = (schemas["schemas"] as Map<*, *>)

            val linemanagerSchema = schemaMap["Linemanager"] as Map<*, *>
            val linemanagerProps =
                (linemanagerSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            // Reflect Kotlin Linemanager
            val lmKotlinProps = Linemanager::class.memberProperties.map { it.name }.toSet()
            logger.info(lmKotlinProps.toString())
            // Root properties must include at least those from the Kotlin data class
            linemanagerProps.shouldContainAll(lmKotlinProps)

            // Nested manager schema
            val managerNode =
                (linemanagerSchema["properties"] as Map<*, *>)

            managerNode.keys shouldContain "manager"
            val managerProps = managerNode["manager"] as Map<*, *>
            val managerRef = managerProps[$$"$ref"]
            managerRef shouldBe "#/components/schemas/Manager"
        }

        "Manager schema exists in OpenAPI documentation and matches domain" {
            val yamlText = this::class.java.classLoader
                .getResource("openapi/documentation.yaml")!!
                .readText()

            val root = Yaml().load<Map<String, Any>>(yamlText)
            val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>

            val managerSchema = schemas["Manager"] as Map<*, *>?
            managerSchema shouldNotBe null
            val managerProps =
                (managerSchema!!["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            val managerKotlinProps = Manager::class.memberProperties.map { it.name }.toSet()

            managerProps.shouldContainAll(managerKotlinProps)
        }
    })
