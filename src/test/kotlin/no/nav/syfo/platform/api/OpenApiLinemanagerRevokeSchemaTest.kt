package no.nav.syfo.platform.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerRevoke
import kotlin.reflect.full.memberProperties

class OpenApiLinemanagerRevokeSchemaTest :
    FunSpec({

        test("openapi LinemanagerRevoke schema matches LinemanagerRevoke domain properties") {
            val schemas = openApiSchemas(PUBLIC_OPENAPI)

            val revokeSchema = schemas["LinemanagerRevoke"] as Map<*, *>
            val revokeProps = (revokeSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            val domainProps = LinemanagerRevoke::class.memberProperties.map { it.name }.toSet()
            revokeProps.shouldContainAll(domainProps)
        }
    })
