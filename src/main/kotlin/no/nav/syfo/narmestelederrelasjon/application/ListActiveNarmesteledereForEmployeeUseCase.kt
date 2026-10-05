package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.EmailAddress
import no.nav.syfo.narmestelederrelasjon.domain.ParsedEmailAddresses
import java.time.Instant
import java.util.UUID

class ListActiveNarmesteledereForEmployeeUseCase(
    private val repository: EmployeeNarmestelederrelasjonRepository,
    private val discardedEmailAddressMetrics: DiscardedEmailAddressMetrics,
) {
    suspend fun execute(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber?): List<EmployeeNarmesteleder> {
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
