package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

internal class FakeBehovRepository(
    private val behov: Narmestelederbehov?,
    private val effects: MutableList<String> = mutableListOf(),
) : NarmestelederbehovRepository {

    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = behov.also { effects += "load" }

    override suspend fun markFulfilled(id: NarmestelederbehovId) {
        effects += "fulfilled"
    }
}

internal class FakeOrganizationAccess(
    private val result: OrganizationAccessResult = OrganizationAccessResult.Granted,
    private val effects: MutableList<String> = mutableListOf(),
) : OrganizationAccess {
    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ) = result.also { effects += "access" }
}

internal class FakeActiveSykmeldingLookup(
    private val result: Boolean = true,
    private val failure: Throwable? = null,
    private val effects: MutableList<String> = mutableListOf(),
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(personIdent: PersonIdent, organizationNumber: OrganizationNumber): Boolean {
        effects += "sykmelding"
        failure?.let { throw it }
        return result
    }
}

internal class FakeEmploymentLookup(
    private val result: Boolean = true,
    private val effects: MutableList<String> = mutableListOf(),
) : EmploymentLookup {

    override suspend fun hasEmployment(personIdent: PersonIdent, organizationNumber: OrganizationNumber) = result.also { effects += "employment" }
}

internal class FakePersonLookup(
    private val people: Map<PersonIdent, PersonNameDetails>,
    private val effects: MutableList<String> = mutableListOf(),
) : PersonLookup {

    override suspend fun find(
        personIdent: PersonIdent,
    ) = people[personIdent].also { effects += "person:${personIdent.value}" }
}

internal class FakeRelationEstablisher(
    private val effects: MutableList<String> = mutableListOf(),
) : EstablishNarmestelederrelasjon {
    var command: EstablishNarmestelederrelasjonCommand? = null

    override suspend fun establish(command: EstablishNarmestelederrelasjonCommand) {
        effects += "establish"
        this.command = command
    }
}

internal class FakeDialog(
    private val attempt: DialogportenCompletionAttempt = DialogportenCompletionAttempt.Completed,
    private val effects: MutableList<String> = mutableListOf(),
) : NarmestelederbehovDialog {
    override suspend fun attemptCompletion(id: NarmestelederbehovId) = attempt.also { effects += "dialog" }
}
