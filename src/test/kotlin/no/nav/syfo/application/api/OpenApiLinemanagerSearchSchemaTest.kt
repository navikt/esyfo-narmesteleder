package no.nav.syfo.application.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.narmesteleder.domain.LinemanagerManagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerPersonRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchPageInfo
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.domain.Name
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.full.memberProperties

class OpenApiLinemanagerSearchSchemaTest :
    StringSpec({
        fun schemas(): Map<*, *> {
            val yamlText = this::class.java.classLoader
                .getResource("openapi/documentation.yaml")!!
                .readText()

            val root = Yaml().load<Map<String, Any>>(yamlText)
            return (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
        }

        "openapi LinemanagerSearchRequest schema matches domain properties" {
            val requestSchema = schemas()["LinemanagerSearchRequest"] as Map<*, *>
            val requestProps =
                (requestSchema["properties"] as Map<*, *>).keys.map { it as String }.toSet()

            val domainProps = LinemanagerSearchRequest::class.memberProperties.map { it.name }.toSet()
            requestProps.shouldContainAll(domainProps)
        }

        "openapi LinemanagerReadCollection schema matches domain properties and refs" {
            val collectionSchema = schemas()["LinemanagerReadCollection"] as Map<*, *>
            val collectionProps = collectionSchema["properties"] as Map<*, *>

            collectionProps.keys.map { it as String }.toSet()
                .shouldContainAll(LinemanagerReadCollection::class.memberProperties.map { it.name }.toSet())
            (collectionProps["meta"] as Map<*, *>)[$$"$ref"] shouldBe "#/components/schemas/LinemanagerSearchPageInfo"
        }

        "openapi linemanager search read schemas match domain properties" {
            val schemaMap = schemas()

            (schemaMap["LinemanagerRead"] as Map<*, *>)["properties"]
                .let { it as Map<*, *> }
                .keys.map { it as String }.toSet()
                .shouldContainAll(LinemanagerRead::class.memberProperties.map { it.name }.toSet())

            (schemaMap["LinemanagerPersonRead"] as Map<*, *>)["properties"]
                .let { it as Map<*, *> }
                .keys.map { it as String }.toSet()
                .shouldContainAll(LinemanagerPersonRead::class.memberProperties.map { it.name }.toSet())

            (schemaMap["LinemanagerManagerRead"] as Map<*, *>)["properties"]
                .let { it as Map<*, *> }
                .keys.map { it as String }.toSet()
                .shouldContainAll(LinemanagerManagerRead::class.memberProperties.map { it.name }.toSet())

            val pageInfoSchema = schemaMap["LinemanagerSearchPageInfo"] as Map<*, *>
            val pageInfoDomainProps = LinemanagerSearchPageInfo::class.memberProperties.map { it.name }.toSet()

            (pageInfoSchema["properties"] as Map<*, *>)
                .keys.map { it as String }.toSet() shouldBe pageInfoDomainProps

            (pageInfoSchema["required"] as List<*>)
                .map { it as String }.toSet() shouldBe pageInfoDomainProps

            val nameSchema = schemaMap["Name"] as Map<*, *>?
            nameSchema shouldNotBe null
            (nameSchema!!["properties"] as Map<*, *>).keys.map { it as String }.toSet()
                .shouldContainAll(Name::class.memberProperties.map { it.name }.toSet())
        }
    })
