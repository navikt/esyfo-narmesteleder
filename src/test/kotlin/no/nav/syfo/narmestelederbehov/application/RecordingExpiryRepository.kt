package no.nav.syfo.narmestelederbehov.application

import java.time.LocalDate

/** Returns queued [results] in order, then 0 (nothing more to expire). */
internal class RecordingExpiryRepository(results: List<Int> = emptyList()) : ExpireNarmestelederbehovRepository {
    private val results = ArrayDeque(results)
    val calls = mutableListOf<Pair<LocalDate, Int>>()

    override suspend fun expireBehov(sykmeldingMaxDate: LocalDate, limit: Int): Int {
        calls += sykmeldingMaxDate to limit
        return results.removeFirstOrNull() ?: 0
    }
}
