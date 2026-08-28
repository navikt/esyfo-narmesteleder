package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.api.internal.v1.LINEMANAGER_REVOKE_BY_ID_PATH
import org.yaml.snakeyaml.Yaml

class OpenApiInternalLinemanagerRevokeSchemaTest :
    StringSpec({

        val yamlText = OpenApiInternalLinemanagerRevokeSchemaTest::class.java.classLoader
            .getResource("openapi/internal-documentation.yaml")!!
            .readText()
        val root = Yaml().load<Map<String, Any>>(yamlText)
        val paths = root["paths"] as Map<*, *>
        val documentedPath = "$INTERNAL_API_V1_PATH$LINEMANAGER_REVOKE_BY_ID_PATH"

        "openapi documents the revoke endpoint on the path registered in code" {
            paths.keys.map { it as String } shouldContain documentedPath
        }

        "openapi documents the revoke responses the endpoint can return" {
            val delete = (paths[documentedPath] as Map<*, *>)["delete"] as Map<*, *>
            val responses = (delete["responses"] as Map<*, *>).keys.map { it.toString() }

            responses shouldContainExactly listOf("202", "400", "401", "403", "404", "500")
        }

        "openapi documents the revoke endpoint as TokenX and Maskinporten" {
            val delete = (paths[documentedPath] as Map<*, *>)["delete"] as Map<*, *>
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
    })
