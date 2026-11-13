package no.nav.syfo.organisasjonstilgang.api

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.platform.api.INTERNAL_OPENAPI
import no.nav.syfo.platform.api.openApiSchemas
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

class AccessibleOrganizationsOpenApiSchemaTest :
    FunSpec({
        test("OpenAPI schemas match the accessible organizations response properties and required fields") {
            assertSoftly {
                schemaShouldMatch("AccessibleOrganizations", AccessibleOrganizationsResponse::class)
                schemaShouldMatch("AccessibleOrganization", AccessibleOrganizationResponse::class)
            }
        }
    })

private fun schemaShouldMatch(schemaName: String, responseClass: KClass<*>) {
    val schema = openApiSchemas(INTERNAL_OPENAPI)[schemaName] as Map<*, *>
    val responseProperties = responseClass.memberProperties.map { it.name }.toSet()

    (schema["properties"] as Map<*, *>).keys.map { it as String }.toSet() shouldBe responseProperties
    (schema["required"] as List<*>).map { it as String }.toSet() shouldBe responseProperties
}
