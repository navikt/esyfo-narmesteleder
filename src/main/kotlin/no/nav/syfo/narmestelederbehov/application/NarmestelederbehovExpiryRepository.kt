package no.nav.syfo.narmestelederbehov.application

import java.time.LocalDate

interface NarmestelederbehovExpiryRepository {
    /**
     * Sets up to [limit] open behov to BEHOV_EXPIRED when a sendt sykmelding for the same employee
     * and organization has tom strictly before [sykmeldingMaxDate]. Returns the number of expired behov.
     */
    suspend fun expireBehov(sykmeldingMaxDate: LocalDate, limit: Int): Int
}
