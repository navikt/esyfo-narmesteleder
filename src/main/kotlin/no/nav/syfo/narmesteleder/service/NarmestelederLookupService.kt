package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.db.INarmestelederLookupDb
import no.nav.syfo.narmesteleder.domain.EmailAddress
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.splitEmailAddresses
import org.slf4j.LoggerFactory

data class NarmestelederLookup(
    val fnr: PersonalIdentificationNumber,
    val epostadresser: List<EmailAddress>,
)

class NarmestelederLookupService(
    private val narmestelederLookupDb: INarmestelederLookupDb,
) {
    suspend fun findActiveNarmesteleder(
        sykmeldtFnr: PersonalIdentificationNumber,
        orgnummer: OrganizationNumber,
    ): NarmestelederLookup? {
        val activeRelations = narmestelederLookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer)
        if (activeRelations.size > 1) {
            logger.error("Multiple active narmesteleder relations found for a sykmeldt and organization")
        }
        return activeRelations.firstOrNull()?.let { relation ->
            NarmestelederLookup(
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
