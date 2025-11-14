package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class OpenApiLinemanagerRevokeSchemaTest : StringSpec({

    "openapi LinemanagerRevoke schema matches LinemanagerRevoke domain properties" {
        val yamlText = this::class.java.classLoader
            .getResource("openapi/documentation.yaml")!!
            .readText()

        val root = Yaml().load<Map<String, Any>>(yamlText)
        val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>

        val revokeSchema = schemas["LinemanagerRevoke"] as Map<*, *>
        val revokeProps = (revokeSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()

        val domainProps = LinemanagerRevoke::class.memberProperties.map { it.name }.toSet()
        revokeProps.shouldContainAll(domainProps)
    }
})
