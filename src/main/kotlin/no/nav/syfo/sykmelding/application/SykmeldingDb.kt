package no.nav.syfo.sykmelding.application

import java.time.LocalDate
import java.util.UUID

interface SykmeldingDb {
    suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit)
}

interface SykmeldingTransaction {
    /**
     * Saves each sykmelding as the current one for its arbeidsforhold (fnr + orgnummer), unless the stored one
     * has a later tom. Returns the number of stored rows replaced because they had the same sykmeldingId.
     */
    fun saveLatestPerArbeidsforhold(sykmeldinger: List<SendtSykmelding>): Int

    fun revoke(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
}
