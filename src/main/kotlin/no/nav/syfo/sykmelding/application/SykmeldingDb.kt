package no.nav.syfo.sykmelding.application

import java.time.LocalDate
import java.util.UUID

interface SykmeldingDb {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmelding?
    suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit)
}

interface SykmeldingTransaction {
    fun batchUpsertSykmeldingerIfMoreRecentTom(sykmeldinger: List<SendtSykmelding>)
    fun batchRevokeSykmelding(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
    fun batchDeleteAllBySykmeldingIds(sykmeldingIds: List<UUID>): Int
}
