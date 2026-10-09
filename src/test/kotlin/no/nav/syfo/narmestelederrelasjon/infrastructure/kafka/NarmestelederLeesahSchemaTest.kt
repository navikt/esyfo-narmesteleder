package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.reflect.full.memberProperties

class NarmestelederLeesahSchemaTest :
    FunSpec({
        val schema = jacksonObjectMapper().readTree(File("docs/kafka/syfo-narmesteleder-leesah.schema.json"))
        val schemaProperties = schema["properties"]
        val messageProperties = NarmestelederLeesahKafkaMessage::class.memberProperties

        test("schema documents exactly the message properties") {
            schemaProperties.fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder
                messageProperties.map { it.name }
        }

        test("schema requires every property because upstream always writes all of them") {
            schema["required"].map { it.asText() } shouldContainExactlyInAnyOrder
                messageProperties.map { it.name }
        }

        test("schema nullability matches the message") {
            messageProperties.forEach { property ->
                val allowsNull = schemaProperties[property.name]["type"].typeNames().contains("null")
                Pair(property.name, allowsNull) shouldBe Pair(property.name, property.returnType.isMarkedNullable)
            }
        }

        test("schema status values match the statuses the message understands") {
            schemaProperties["status"]["examples"].filterNot { it.isNull }.map { it.asText() } shouldContainExactlyInAnyOrder
                LeesahStatus.entries.filterNot { it == LeesahStatus.UKJENT }.map { it.name }
        }
    })

private fun JsonNode.typeNames(): List<String> = if (isArray) map { it.asText() } else listOf(asText())
