package no.nav.syfo.narmestelederrelasjon.api.model

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.narmestelederrelasjon.domain.RelationPersonName
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
    val name: RelationPersonNameResponse,
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
    employeeName: RelationPersonName,
    organizationName: String,
) = NarmestelederrelasjonApiResponse(
    linemanagerRelation = NarmestelederrelasjonResponse(
        id = id,
        employee = employee.toResponse(employeeName),
        organization = OrganizationResponse(orgNumber, organizationName),
    )
)

private fun RelationPerson.toResponse(name: RelationPersonName) = RelationPersonResponse(
    name = name.toResponse(),
    nationalIdentificationNumber = nationalIdentificationNumber,
)

private fun RelationPersonName.toResponse() = RelationPersonNameResponse(
    firstName = firstName,
    middleName = middleName,
    lastName = lastName,
)
