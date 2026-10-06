package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import kotlinx.serialization.json.Json

data class AltinnAuthorizationRequest(
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

sealed class User(
    val id: String,
    val attributeId: String,
)

class Person(
    id: String,
) : User(id, "urn:altinn:person:identifier-no")

class System(
    id: String,
) : User(id, "urn:altinn:systemuser:uuid")

fun createAltinnAuthorizationRequest(
    user: User,
    orgNumberSet: Set<String>,
    resource: String,
) = AltinnAuthorizationRequest(
    request =
    AltinnAuthorizationRequest.XacmlJsonRequestExternal(
        returnPolicyIdList = true,
        accessSubject =
        listOf(
            AltinnAuthorizationRequest.XacmlJsonCategoryExternal(
                attribute =
                listOf(
                    AltinnAuthorizationRequest.XacmlJsonAttributeExternal(
                        attributeId = user.attributeId,
                        value = user.id,
                    ),
                ),
            ),
        ),
        action =
        listOf(
            AltinnAuthorizationRequest.XacmlJsonCategoryExternal(
                attribute =
                listOf(
                    AltinnAuthorizationRequest.XacmlJsonAttributeExternal(
                        attributeId = "urn:oasis:names:tc:xacml:1.0:action:action-id",
                        value = "access",
                        dataType = "http://www.w3.org/2001/XMLSchema#string",
                    ),
                ),
            ),
        ),
        resource =
        orgNumberSet.map { orgnr ->
            AltinnAuthorizationRequest.XacmlJsonCategoryExternal(
                attribute =
                listOf(
                    AltinnAuthorizationRequest.XacmlJsonAttributeExternal(
                        attributeId = "urn:altinn:resource",
                        value = resource,
                    ),
                    AltinnAuthorizationRequest.XacmlJsonAttributeExternal(
                        attributeId = "urn:altinn:organization:identifier-no",
                        value = orgnr,
                    ),
                ),
            )
        },
    ),
)
