package no.nav.syfo.sykmelding.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.OffsetDateTime
import java.util.UUID

object SendtSykmeldingNarmestelederBruddTable : Table("sendt_sykmelding_narmesteleder_brudd") {
    val id = javaUUID("id").databaseGenerated()
    val sykmeldingId = javaUUID("sykmelding_id").uniqueIndex()
    val fnr = text("fnr")
    val orgnummer = text("orgnummer")
    val kafkaTopic = text("kafka_topic")
    val kafkaPartition = integer("kafka_partition")
    val kafkaOffset = long("kafka_offset")
    val kilde = text("kilde")
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)

    override val primaryKey = PrimaryKey(id)
}

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

interface ISendtSykmeldingNarmestelederBruddRepository {
    suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmeldingNarmestelederBrudd?
    suspend fun insert(brudd: SendtSykmeldingNarmestelederBrudd)
}

class SendtSykmeldingNarmestelederBruddRepository(
    private val database: Database,
) : ISendtSykmeldingNarmestelederBruddRepository {

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmeldingNarmestelederBrudd? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            SendtSykmeldingNarmestelederBruddTable
                .selectAll()
                .where { SendtSykmeldingNarmestelederBruddTable.sykmeldingId eq sykmeldingId }
                .singleOrNull()
                ?.toSendtSykmeldingNarmestelederBrudd()
        }
    }

    override suspend fun insert(brudd: SendtSykmeldingNarmestelederBrudd) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                SendtSykmeldingNarmestelederBruddTable.insert {
                    it[sykmeldingId] = brudd.sykmeldingId
                    it[fnr] = brudd.fnr
                    it[orgnummer] = brudd.orgnummer
                    it[kafkaTopic] = brudd.kafkaTopic
                    it[kafkaPartition] = brudd.kafkaPartition
                    it[kafkaOffset] = brudd.kafkaOffset
                    it[kilde] = brudd.kilde
                    it[created] = brudd.created
                }
            }
        }
    }
}

private fun ResultRow.toSendtSykmeldingNarmestelederBrudd(): PersistedSendtSykmeldingNarmestelederBrudd = PersistedSendtSykmeldingNarmestelederBrudd(
    id = this[SendtSykmeldingNarmestelederBruddTable.id],
    sykmeldingId = this[SendtSykmeldingNarmestelederBruddTable.sykmeldingId],
    fnr = this[SendtSykmeldingNarmestelederBruddTable.fnr],
    orgnummer = this[SendtSykmeldingNarmestelederBruddTable.orgnummer],
    kafkaTopic = this[SendtSykmeldingNarmestelederBruddTable.kafkaTopic],
    kafkaPartition = this[SendtSykmeldingNarmestelederBruddTable.kafkaPartition],
    kafkaOffset = this[SendtSykmeldingNarmestelederBruddTable.kafkaOffset],
    kilde = this[SendtSykmeldingNarmestelederBruddTable.kilde],
    created = this[SendtSykmeldingNarmestelederBruddTable.created],
)
