package no.nav.syfo.platform.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.narmestelederrelasjon.api.model.Linemanager
import no.nav.syfo.narmestelederrelasjon.api.model.Manager
import no.nav.syfo.platform.observability.logger
import kotlin.reflect.full.memberProperties
import no.nav.syfo.narmestelederbehov.api.Manager as FulfillmentManager

class OpenApiLinemanagerSchemaTest :
    FunSpec({
        val logger = logger()

        test("openapi Linemanager schema matches Linemanager and nested Manager properties") {
            val schemaMap = openApiSchemas(PUBLIC_OPENAPI)

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

        test("Manager schema exists in OpenAPI documentation and matches domain") {
            val schemas = openApiSchemas(PUBLIC_OPENAPI)

            val managerSchema = schemas["Manager"] as Map<*, *>?
            managerSchema shouldNotBe null
            val managerProps =
                (managerSchema!!["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            listOf(Manager::class, FulfillmentManager::class).forEach { manager ->
                managerProps.shouldContainAll(manager.memberProperties.map { it.name }.toSet())
            }
        }
    })
