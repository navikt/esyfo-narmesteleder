package no.nav.syfo.narmestelederrelasjon.application.employee

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.DiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.domain.EmailAddress
import no.nav.syfo.narmestelederrelasjon.domain.ParsedEmailAddresses
import java.time.Instant
import java.util.UUID

data class EmployeeNarmestelederrelasjon(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val activeFrom: Instant,
    val managerFirstName: String?,
    val managerMiddleName: String?,
    val managerLastName: String?,
    val managerEmail: String,
    val managerMobile: String,
)

interface EmployeeNarmestelederrelasjonRepository {
    suspend fun findActive(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber?): List<EmployeeNarmestelederrelasjon>
}

class ListActiveNarmesteledereForEmployeeUseCase(
    private val repository: EmployeeNarmestelederrelasjonRepository,
    private val discardedEmailAddressMetrics: DiscardedEmailAddressMetrics,
) {
    suspend fun execute(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber?): List<EmployeeNarmesteleder> {
        val relations = repository.findActive(employeeIdent, organizationNumber)
        val parsedEmailAddresses = relations.map { EmailAddress.parseSeparatedList(it.managerEmail) }
        discardedEmailAddressMetrics.record(parsedEmailAddresses.sumOf { it.discardedEmailAddressCount })
        return relations.zip(parsedEmailAddresses, ::toEmployeeNarmesteleder)
    }
}

data class EmployeeNarmesteleder(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val activeFrom: Instant,
    val name: NarmestelederName?,
    val emailAddresses: List<EmailAddress>,
    val mobile: String,
)

data class NarmestelederName(
    val firstName: String,
    val middleName: String?,
    val lastName: String,
)

private fun toEmployeeNarmesteleder(
    relation: EmployeeNarmestelederrelasjon,
    emailAddresses: ParsedEmailAddresses,
) = EmployeeNarmesteleder(
    id = relation.id,
    organizationNumber = relation.organizationNumber,
    activeFrom = relation.activeFrom,
    name = relation.managerName(),
    emailAddresses = emailAddresses.validEmailAddresses,
    mobile = relation.managerMobile,
)

private fun EmployeeNarmestelederrelasjon.managerName(): NarmestelederName? {
    val firstName = managerFirstName ?: return null
    val lastName = managerLastName ?: return null
    return NarmestelederName(firstName, managerMiddleName, lastName)
}
