package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AltinnAuthorizationSerializationTest :
    FunSpec({
        val mapper = jacksonObjectMapper()

        listOf(
            Person("synthetic-person") to "urn:altinn:person:identifier-no",
            System("synthetic-system-user") to "urn:altinn:systemuser:uuid",
        ).forEach { (user, attributeId) ->
            test("keeps the authorization request JSON shape for $attributeId") {
                val request = createAltinnAuthorizationRequest(user, setOf("910000001"), "synthetic-resource")

                mapper.readTree(mapper.writeValueAsString(request)) shouldBe mapper.readTree(
                    """
                    {
                      "request": {
                        "returnPolicyIdList": true,
                        "accessSubject": [{
                          "attribute": [{
                            "attributeId": "$attributeId",
                            "value": "${user.id}",
                            "dataType": null
                          }]
                        }],
                        "action": [{
                          "attribute": [{
                            "attributeId": "urn:oasis:names:tc:xacml:1.0:action:action-id",
                            "value": "access",
                            "dataType": "http://www.w3.org/2001/XMLSchema#string"
                          }]
                        }],
                        "resource": [{
                          "attribute": [
                            {
                              "attributeId": "urn:altinn:resource",
                              "value": "synthetic-resource",
                              "dataType": null
                            },
                            {
                              "attributeId": "urn:altinn:organization:identifier-no",
                              "value": "910000001",
                              "dataType": null
                            }
                          ]
                        }]
                      }
                    }
                    """.trimIndent(),
                )
            }
        }

        Decision.entries.forEach { decision ->
            test("keeps the authorization response JSON shape for $decision") {
                val json = """{"response":[{"decision":"$decision"}]}"""
                val response = mapper.readValue(json, AltinnAuthorizationResponse::class.java)

                response.result() shouldBe decision
                mapper.readTree(mapper.writeValueAsString(response)) shouldBe mapper.readTree(json)
            }
        }

        test("returns the first decision when the response has several decisions") {
            val response = AltinnAuthorizationResponse(
                listOf(DecisionResult(Decision.Deny), DecisionResult(Decision.Permit)),
            )

            response.result() shouldBe Decision.Deny
        }
    })
