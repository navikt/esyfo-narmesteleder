package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.EmailAddress
import no.nav.syfo.narmestelederrelasjon.domain.ParsedEmailAddresses
import java.time.Instant
import java.util.UUID

class ListActiveNarmesteledereForEmployeeUseCase(
    private val repository: EmployeeNarmestelederrelasjonRepository,
) {
    suspend fun execute(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber?): ActiveNarmesteledereForEmployee {
        val relations = repository.findActive(employeeIdent, organizationNumber)
        val parsedEmailAddresses = relations.map { EmailAddress.parseSeparatedList(it.managerEmail) }
        return ActiveNarmesteledereForEmployee(
            narmesteledere = relations.zip(parsedEmailAddresses, ::toEmployeeNarmesteleder),
            discardedEmailAddressCount = parsedEmailAddresses.sumOf { it.discardedEmailAddressCount },
        )
    }
}

data class ActiveNarmesteledereForEmployee(
    val narmesteledere: List<EmployeeNarmesteleder>,
    val discardedEmailAddressCount: Int,
)

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
