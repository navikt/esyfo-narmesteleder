package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import java.util.UUID

data class NarmestelederrelasjonApiResponse(
    val linemanagerRelation: NarmestelederrelasjonResponse
)

data class NarmestelederrelasjonResponse(
    val id: UUID,
    val employee: RelationPersonResponse,
    val organization: OrganizationResponse,
)

data class RelationPersonResponse(
    val name: RelationPersonNameResponse?,
    val nationalIdentificationNumber: String,
)

data class RelationPersonNameResponse(
    val firstName: String,
    val middleName: String?,
    val lastName: String,
)

data class OrganizationResponse(
    val orgNumber: String,
    val name: String,
)

fun Narmestelederrelasjon.toResponse(
    organizationName: String,
) = NarmestelederrelasjonApiResponse(
    linemanagerRelation = NarmestelederrelasjonResponse(
        id = id,
        employee = employee.toResponse(),
        organization = OrganizationResponse(orgNumber.value, organizationName),
    )
)

private fun RelationPerson.toResponse() = RelationPersonResponse(
    name = if (firstName != null && lastName != null) {
        RelationPersonNameResponse(
            firstName = firstName,
            middleName = middleName,
            lastName = lastName,
        )
    } else {
        null
    },
    nationalIdentificationNumber = personIdent.value,
)
