package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

internal class FakeBehovRepository(
    private val behov: Narmestelederbehov?,
    private val effects: MutableList<String> = mutableListOf(),
    private val updateFailure: Throwable? = null,
) : NarmestelederbehovRepository {

    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = behov.also { effects += "load" }

    override suspend fun markFulfilled(id: NarmestelederbehovId) {
        effects += "fulfilled"
        updateFailure?.let { throw it }
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
    private val people: Map<PersonIdent, PersonDetails>,
    private val effects: MutableList<String> = mutableListOf(),
) : PersonLookup {

    override suspend fun find(
        personIdent: PersonIdent,
    ) = people[personIdent].also { effects += "person:${personIdent.value}" }
}

internal class FakeRelationEstablisher(
    private val effects: MutableList<String> = mutableListOf(),
    private val failure: Throwable? = null,
) : EstablishNarmestelederrelasjon {
    var command: EstablishNarmestelederrelasjonCommand? = null

    override suspend fun establish(command: EstablishNarmestelederrelasjonCommand) {
        effects += "establish"
        failure?.let { throw it }
        this.command = command
    }
}

internal class FakeDialog(
    private val attempt: DialogportenCompletionAttempt = DialogportenCompletionAttempt.Completed,
    private val effects: MutableList<String> = mutableListOf(),
    private val failure: Throwable? = null,
) : NarmestelederbehovDialog {
    override suspend fun attemptCompletion(id: NarmestelederbehovId): DialogportenCompletionAttempt {
        effects += "dialog"
        failure?.let { throw it }
        return attempt
    }
}
