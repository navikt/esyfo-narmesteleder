package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.upstream.UpstreamFailure
import java.util.UUID

fun interface CreateNarmestelederbehov {
    suspend fun execute(command: CreateNarmestelederbehovCommand): CreateNarmestelederbehovResult
}

data class CreateNarmestelederbehovCommand(
    val employee: Employee,
    val manager: PersonIdent?,
    val reason: BehovReason,
    val revokedRelationId: UUID?,
    /** True when the caller already knows that the employee has an active sykmelding, so Dinesykmeldte is not asked. */
    val sykmeldingKnownActive: Boolean,
    val mainOrganization: MainOrganizationSource,
    val source: NarmestelederbehovSource,
)

sealed interface MainOrganizationSource {
    /** The main organization reported on the sykmelding; stored as received, without validation. */
    data class FromSykmelding(val mainOrganizationNumber: String?) : MainOrganizationSource

    /** The main organization is looked up from the employee's employment in Aareg. */
    data object FromEmployment : MainOrganizationSource
}

sealed interface NarmestelederbehovSource {
    data class SendtSykmelding(val sykmeldingId: String) : NarmestelederbehovSource
    data class NarmestelederLeesah(val relationId: UUID) : NarmestelederbehovSource
}

sealed interface CreateNarmestelederbehovResult {
    data class Created(val id: NarmestelederbehovId) : CreateNarmestelederbehovResult
    data object Disabled : CreateNarmestelederbehovResult
    data object AlreadyExists : CreateNarmestelederbehovResult
    data object NoActiveSykmelding : CreateNarmestelederbehovResult

    /** Nothing is stored; the caller decides whether to retry. */
    data class UpstreamUnavailable(val failure: UpstreamFailure) : CreateNarmestelederbehovResult
}
