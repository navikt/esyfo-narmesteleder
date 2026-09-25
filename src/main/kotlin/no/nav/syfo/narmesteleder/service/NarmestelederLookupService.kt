package no.nav.syfo.narmesteleder.service

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmesteleder.db.NarmestelederLookupDb
import no.nav.syfo.narmesteleder.domain.EmailAddress
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.splitEmailAddresses
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

private data class MultipleActiveRelationsDetails(
    val recordCount: Int,
)

private val multipleActiveRelations = applicationEvent<MultipleActiveRelationsDetails>(
    name = "multiple_active_relations",
    level = Level.ERROR,
    message = "Multiple active nearest leader relations were found; selecting the first",
    fields = mapOf(
        "record_count" to { it.recordCount },
    ),
)

data class NarmestelederLookup(
    val id: UUID,
    val fnr: PersonalIdentificationNumber,
    val epostadresser: List<EmailAddress>,
)

class NarmestelederLookupService(
    private val narmestelederLookupDb: NarmestelederLookupDb,
) {
    suspend fun hasActiveNarmesteleder(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): Boolean = narmestelederLookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer).isNotEmpty()

    suspend fun findActiveNarmesteleder(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): NarmestelederLookup? {
        val activeRelations = narmestelederLookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer)
        if (activeRelations.size > 1) {
            logger.logEvent(multipleActiveRelations, MultipleActiveRelationsDetails(recordCount = activeRelations.size))
        }
        return activeRelations.firstOrNull()?.let { relation ->
            NarmestelederLookup(
                id = relation.narmestelederId,
                fnr = relation.narmestelederFnr,
                epostadresser = relation.narmestelederEpost
                    .splitEmailAddresses()
                    .map(::EmailAddress),
            )
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(NarmestelederLookupService::class.java)
    }
}
