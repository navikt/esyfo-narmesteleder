package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederrelasjon.domain.EmailAddress
import java.util.UUID

data class ActiveNarmesteleder(
    val id: UUID,
    val managerIdent: PersonIdent,
    val emailAddresses: List<EmailAddress>,
)

class LookupActiveNarmestelederUseCase(
    private val repository: ActiveNarmestelederrelasjonRepository,
    private val discardedEmailAddressMetrics: DiscardedEmailAddressMetrics,
) {
    suspend fun execute(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): ActiveNarmesteleder? {
        val activeRelations = repository.findActive(employeeIdent, organizationNumber)
        if (activeRelations.size > 1) {
            logger.event(multipleActiveRelations, activeRelations.size)
        }
        return activeRelations.firstOrNull()?.let { relation ->
            val parsedEmailAddresses = EmailAddress.parseSeparatedList(relation.managerEmail)
            discardedEmailAddressMetrics.record(parsedEmailAddresses.discardedEmailAddressCount)
            ActiveNarmesteleder(
                id = relation.id,
                managerIdent = relation.managerIdent,
                emailAddresses = parsedEmailAddresses.validEmailAddresses,
            )
        }
    }

    private companion object {
        val logger = applicationLogger(LookupActiveNarmestelederUseCase::class.java)
    }
}
