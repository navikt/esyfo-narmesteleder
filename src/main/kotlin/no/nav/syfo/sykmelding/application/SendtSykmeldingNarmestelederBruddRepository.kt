package no.nav.syfo.sykmelding.application

import java.time.OffsetDateTime
import java.util.UUID

data class SendtSykmeldingNarmestelederBrudd(
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val kafkaTopic: String,
    val kafkaPartition: Int,
    val kafkaOffset: Long,
    val kilde: String,
    val created: OffsetDateTime,
)

data class PersistedSendtSykmeldingNarmestelederBrudd(
    val id: UUID,
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val kafkaTopic: String,
    val kafkaPartition: Int,
    val kafkaOffset: Long,
    val kilde: String,
    val created: OffsetDateTime,
)

interface SendtSykmeldingNarmestelederBruddRepository {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmeldingNarmestelederBrudd?
    suspend fun insert(brudd: SendtSykmeldingNarmestelederBrudd)
}
