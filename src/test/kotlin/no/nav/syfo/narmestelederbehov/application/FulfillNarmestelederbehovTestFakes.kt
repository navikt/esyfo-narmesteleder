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

internal class FakeBehovRepository(private val behov: Narmestelederbehov?) : NarmestelederbehovRepository {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = behov.also { effects += "load" }

    override suspend fun markFulfilled(id: NarmestelederbehovId) {
        effects += "fulfilled"
    }
}

internal class FakeOrganizationAccess(
    private val result: OrganizationAccessResult = OrganizationAccessResult.Granted,
) : OrganizationAccess {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun evaluate(
        subject: OrganizationAccessSubject,
        organizationNumber: OrganizationNumber,
    ) = result.also { effects += "access" }
}

internal class FakeActiveSykmeldingLookup(
    private val result: Boolean = true,
    private val failure: Throwable? = null,
) : ActiveSykmeldingLookup {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun hasActiveSykmelding(personIdent: PersonIdent, organizationNumber: OrganizationNumber): Boolean {
        effects += "sykmelding"
        failure?.let { throw it }
        return result
    }
}

internal class FakeEmploymentLookup(private val result: Boolean = true) : EmploymentLookup {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun hasEmployment(personIdent: PersonIdent, organizationNumber: OrganizationNumber) = result.also { effects += "employment" }
}

internal class FakePersonLookup(private val people: Map<PersonIdent, PersonNameDetails>) : PersonLookup {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun find(
        personIdent: PersonIdent,
    ) = people[personIdent].also { effects += "person:${personIdent.value}" }
}

internal class FakeRelationEstablisher : EstablishNarmestelederrelasjon {
    private lateinit var effects: MutableList<String>
    var command: EstablishNarmestelederrelasjonCommand? = null

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun establish(command: EstablishNarmestelederrelasjonCommand) {
        effects += "establish"
        this.command = command
    }
}

internal class FakeDialog(
    private val attempt: DialogportenCompletionAttempt = DialogportenCompletionAttempt.Completed,
) : NarmestelederbehovDialog {
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override suspend fun attemptCompletion(id: NarmestelederbehovId) = attempt.also { effects += "dialog" }
}

internal class FakeFulfillNarmestelederbehovOutcomeLogger : FulfillNarmestelederbehovOutcomeLogger {
    val outcomes = mutableListOf<FulfillNarmestelederbehovOutcome>()
    private lateinit var effects: MutableList<String>

    fun withEffects(effects: MutableList<String>) = apply { this.effects = effects }

    override fun log(outcome: FulfillNarmestelederbehovOutcome) {
        outcomes += outcome
        effects += "log"
    }
}
