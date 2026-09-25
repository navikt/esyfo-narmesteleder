package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmesteleder.api.internal.v1.LINEMANAGER_REVOKE_BY_ID_PATH
import no.nav.syfo.narmestelederrelasjon.api.NARMESTELEDERRELASJON_API_PATH
import org.yaml.snakeyaml.Yaml

class OpenApiInternalLinemanagerRevokeSchemaTest :
    StringSpec({

        val yamlText = OpenApiInternalLinemanagerRevokeSchemaTest::class.java.classLoader
            .getResource("openapi/internal-documentation.yaml")!!
            .readText()
        val root = Yaml().load<Map<String, Any>>(yamlText)
        val paths = root["paths"] as Map<*, *>
        val revokePath = "$INTERNAL_API_V1_PATH$LINEMANAGER_REVOKE_BY_ID_PATH"
        val relationPath = NARMESTELEDERRELASJON_API_PATH

        "openapi documents the revoke endpoint on the path registered in code" {
            paths.keys.map { it as String } shouldContain revokePath
            revokePath shouldBe relationPath
        }

        "openapi documents the revoke responses the endpoint can return" {
            val delete = (paths[revokePath] as Map<*, *>)["delete"] as Map<*, *>
            val responses = (delete["responses"] as Map<*, *>).keys.map { it.toString() }

            responses shouldContainExactly listOf("202", "400", "401", "403", "404", "500")
        }

        "openapi documents the active relation read endpoint and its masked not found response" {
            val get = (paths[relationPath] as Map<*, *>)["get"] as Map<*, *>
            val responses = (get["responses"] as Map<*, *>).keys.map { it.toString() }

            responses shouldContainExactly listOf("200", "401", "404", "500")
            (get["description"] as String).contains("avoid disclosing whether the relation exists") shouldBe true
        }

        "openapi documents the relation read endpoint as TokenX and Maskinporten" {
            val get = (paths[relationPath] as Map<*, *>)["get"] as Map<*, *>
            val security = (get["security"] as List<*>).map { (it as Map<*, *>).keys.single() }

            security shouldContainExactly listOf("maskinporten_jwt", "tokenx_jwt")
        }

        "openapi documents the revoke endpoint as TokenX and Maskinporten" {
            val delete = (paths[revokePath] as Map<*, *>)["delete"] as Map<*, *>
            val security = (delete["security"] as List<*>).map { (it as Map<*, *>).keys.single() }

            security shouldContainExactly listOf("maskinporten_jwt", "tokenx_jwt")
        }

        "openapi ErrorType enum contains the error types the endpoint uses" {
            val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
            val errorTypes = ((schemas["ErrorType"] as Map<*, *>)["enum"] as List<*>).map { it as String }

            errorTypes.shouldContainAll(
                ErrorType.AUTHORIZATION_ERROR.name,
                ErrorType.NOT_FOUND.name,
                ErrorType.BAD_REQUEST.name,
            )
        }

        "openapi declares exactly the read response fields and no additional properties" {
            val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
            val response = schemas["NarmestelederrelasjonApiResponse"] as Map<*, *>
            val relation = schemas["LinemanagerRelation"] as Map<*, *>
            val employee = schemas["LinemanagerRelationPerson"] as Map<*, *>
            val name = schemas["LinemanagerRelationEmployeeName"] as Map<*, *>
            val organization = schemas["LinemanagerRelationOrganization"] as Map<*, *>

            (response["properties"] as Map<*, *>).keys.map { it.toString() } shouldContainExactly
                listOf("linemanagerRelation")
            (response["required"] as List<*>).map { it.toString() } shouldContainExactly listOf("linemanagerRelation")
            response["additionalProperties"] shouldBe false
            (relation["properties"] as Map<*, *>).keys.map { it.toString() } shouldContainExactly
                listOf("id", "employee", "organization")
            (relation["required"] as List<*>).map { it.toString() } shouldContainExactly
                listOf("id", "employee", "organization")
            relation["additionalProperties"] shouldBe false
            (employee["properties"] as Map<*, *>).keys.map { it.toString() } shouldContainExactly
                listOf("name", "nationalIdentificationNumber")
            (employee["required"] as List<*>).map { it.toString() } shouldContainExactly
                listOf("name", "nationalIdentificationNumber")
            employee["additionalProperties"] shouldBe false
            (name["properties"] as Map<*, *>).keys.map { it.toString() } shouldContainExactly
                listOf("firstName", "middleName", "lastName")
            (name["required"] as List<*>).map { it.toString() } shouldContainExactly
                listOf("firstName", "middleName", "lastName")
            name["additionalProperties"] shouldBe false
            (organization["properties"] as Map<*, *>).keys.map { it.toString() } shouldContainExactly
                listOf("orgNumber", "name")
            organization["additionalProperties"] shouldBe false
        }

        "openapi documents no-store on successful and masked responses" {
            val get = (paths[relationPath] as Map<*, *>)["get"] as Map<*, *>
            val responses = get["responses"] as Map<*, *>
            val successfulHeaders = (responses["200"] as Map<*, *>)["headers"] as Map<*, *>
            val notFoundHeaders = (responses["404"] as Map<*, *>)["headers"] as Map<*, *>

            successfulHeaders.keys.map { it.toString() } shouldContainExactly listOf("Cache-Control")
            notFoundHeaders.keys.map { it.toString() } shouldContainExactly listOf("Cache-Control")
        }
    })
