package no.nav.syfo.altinn.pdp.client

import kotlinx.serialization.json.Json

data class PdpRequest(
    val request: XacmlJsonRequestExternal,
) {
    data class XacmlJsonRequestExternal(
        val returnPolicyIdList: Boolean,
        val accessSubject: List<XacmlJsonCategoryExternal>,
        val action: List<XacmlJsonCategoryExternal>,
        val resource: List<XacmlJsonCategoryExternal>,
    )

    data class XacmlJsonCategoryExternal(
        val attribute: List<XacmlJsonAttributeExternal>,
    )

    data class XacmlJsonAttributeExternal(
        val attributeId: String,
        val value: String,
        val dataType: String? = null,
    )

    override fun toString(): String = Json.encodeToString(this)
}

sealed class Bruker(
    val id: String,
    val attributeId: String,
)

class Person(
    id: String,
) : Bruker(id, "urn:altinn:person:identifier-no")

class System(
    id: String,
) : Bruker(id, "urn:altinn:systemuser:uuid")

fun lagPdpRequest(
    bruker: Bruker,
    orgnrSet: Set<String>,
    ressurs: String,
) = PdpRequest(
    request =
        PdpRequest.XacmlJsonRequestExternal(
            returnPolicyIdList = true,
            accessSubject =
                listOf(
                    PdpRequest.XacmlJsonCategoryExternal(
                        attribute =
                            listOf(
                                PdpRequest.XacmlJsonAttributeExternal(
                                    attributeId = bruker.attributeId,
                                    value = bruker.id,
                                ),
                            ),
                    ),
                ),
            action =
                listOf(
                    PdpRequest.XacmlJsonCategoryExternal(
                        attribute =
                            listOf(
                                PdpRequest.XacmlJsonAttributeExternal(
                                    attributeId = "urn:oasis:names:tc:xacml:1.0:action:action-id",
                                    value = "access",
                                    dataType = "http://www.w3.org/2001/XMLSchema#string",
                                ),
                            ),
                    ),
                ),
            resource =
                orgnrSet.map { orgnr ->
                    PdpRequest.XacmlJsonCategoryExternal(
                        attribute =
                            listOf(
                                PdpRequest.XacmlJsonAttributeExternal(
                                    attributeId = "urn:altinn:resource",
                                    value = ressurs,
                                ),
                                PdpRequest.XacmlJsonAttributeExternal(
                                    attributeId = "urn:altinn:organization:identifier-no",
                                    value = orgnr,
                                ),
                            ),
                    )
                },
        ),
)
