package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederrelasjon.api.model.EmployeeLinemanagerResponse
import no.nav.syfo.narmestelederrelasjon.api.model.EmployeeLinemanagersResponse
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class EmployeeLinemanagerOpenApiSchemaTest :
    FunSpec({
        listOf(
            "EmployeeLinemanagerCollection" to EmployeeLinemanagersResponse::class,
            "EmployeeLinemanagerRead" to EmployeeLinemanagerResponse::class,
            "Name" to EmployeeLinemanagerResponse.Name::class,
        ).forEach { (schemaName, responseClass) ->
            test("OpenAPI $schemaName schema matches response properties and required fields") {
                val schema = employeeSchemas()[schemaName] as Map<*, *>
                val properties = (schema["properties"] as Map<*, *>).keys.map { it as String }.toSet()
                val responseProperties = responseClass.memberProperties.map { it.name }.toSet()
                properties shouldBe responseProperties
                (schema["required"] as List<*>).map { it as String }.toSet() shouldBe responseProperties
            }
        }

        test("OpenAPI EmployeeLinemanagerRead schema does not expose national identification number") {
            val schema = employeeSchemas()["EmployeeLinemanagerRead"] as Map<*, *>
            (schema["properties"] as Map<*, *>).keys.map { it as String } shouldNotContain "nationalIdentificationNumber"
        }
    })

private fun employeeSchemas(): Map<*, *> {
    val yamlText = EmployeeLinemanagerOpenApiSchemaTest::class.java.classLoader
        .getResource("openapi/internal-documentation.yaml")
        ?.readText()
        ?: error("Missing internal OpenAPI documentation")
    val root = Yaml().load<Map<String, Any>>(yamlText)
    return (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
}
