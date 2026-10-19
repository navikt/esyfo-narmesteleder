package no.nav.syfo.sykmelding.application

import java.time.LocalDate
import java.util.UUID

interface SykmeldingDb {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity?
    suspend fun transaction(block: suspend SykmeldingTransaction.() -> Unit)
}

interface SykmeldingTransaction {
    fun batchUpsertSykmeldingerIfMoreRecentTom(entities: List<SendtSykmeldingEntity>)
    fun batchRevokeSykmelding(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int
    fun batchDeleteAllBySykmeldingIds(sykmeldingIds: List<UUID>): Int
}
