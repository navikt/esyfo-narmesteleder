package no.nav.syfo.narmestelederbehov.infrastructure

import faker
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

internal data class StoredNarmestelederbehov(
    val id: UUID,
    val orgnummer: String,
    val sykmeldtFnr: String,
    val hovedenhetOrgnummer: String?,
    val narmestelederFnr: String?,
    val fornavn: String?,
    val mellomnavn: String?,
    val etternavn: String?,
    val created: Instant,
    val updated: Instant,
    val behovReason: BehovReason,
    val behovStatus: BehovStatus,
    val dialogId: UUID?,
)

internal fun insertNarmestelederbehov(
    sykmeldtFnr: String = faker.numerify("###########"),
    orgnummer: String = faker.numerify("#########"),
    hovedenhetOrgnummer: String = faker.numerify("#########"),
    narmestelederFnr: String? = faker.numerify("###########"),
    behovReason: BehovReason = BehovReason.DEAKTIVERT_NY_LEDER,
    behovStatus: BehovStatus = BehovStatus.BEHOV_CREATED,
    fornavn: String? = null,
    mellomnavn: String? = null,
    etternavn: String? = null,
    dialogId: UUID? = null,
    created: Instant = Instant.now(),
): StoredNarmestelederbehov = transaction(TestDB.exposedDatabase) {
    val id = UUID.randomUUID()
    val createdAt = OffsetDateTime.ofInstant(created, ZoneOffset.UTC)
    NarmestelederbehovTable.insert {
        it[NarmestelederbehovTable.id] = id
        it[NarmestelederbehovTable.orgnummer] = orgnummer
        it[NarmestelederbehovTable.sykmeldtFnr] = sykmeldtFnr
        it[NarmestelederbehovTable.hovedenhetOrgnummer] = hovedenhetOrgnummer
        it[NarmestelederbehovTable.narmestelederFnr] = narmestelederFnr
        it[NarmestelederbehovTable.fornavn] = fornavn
        it[NarmestelederbehovTable.mellomnavn] = mellomnavn
        it[NarmestelederbehovTable.etternavn] = etternavn
        it[NarmestelederbehovTable.created] = createdAt
        it[NarmestelederbehovTable.updated] = createdAt
        it[NarmestelederbehovTable.behovReason] = behovReason.name
        it[NarmestelederbehovTable.behovStatus] = behovStatus
        it[NarmestelederbehovTable.dialogId] = dialogId
    }
    requireNotNull(selectStoredNarmestelederbehov(id))
}

internal fun findStoredNarmestelederbehov(id: UUID): StoredNarmestelederbehov? = transaction(TestDB.exposedDatabase) {
    selectStoredNarmestelederbehov(id)
}

private fun selectStoredNarmestelederbehov(id: UUID): StoredNarmestelederbehov? = NarmestelederbehovTable
    .selectAll()
    .where { NarmestelederbehovTable.id eq id }
    .singleOrNull()
    ?.toStoredNarmestelederbehov()

private fun ResultRow.toStoredNarmestelederbehov() = StoredNarmestelederbehov(
    id = this[NarmestelederbehovTable.id],
    orgnummer = this[NarmestelederbehovTable.orgnummer],
    sykmeldtFnr = this[NarmestelederbehovTable.sykmeldtFnr],
    hovedenhetOrgnummer = this[NarmestelederbehovTable.hovedenhetOrgnummer],
    narmestelederFnr = this[NarmestelederbehovTable.narmestelederFnr],
    fornavn = this[NarmestelederbehovTable.fornavn],
    mellomnavn = this[NarmestelederbehovTable.mellomnavn],
    etternavn = this[NarmestelederbehovTable.etternavn],
    created = this[NarmestelederbehovTable.created].toInstant(),
    updated = this[NarmestelederbehovTable.updated].toInstant(),
    behovReason = BehovReason.valueOf(this[NarmestelederbehovTable.behovReason]),
    behovStatus = this[NarmestelederbehovTable.behovStatus],
    dialogId = this[NarmestelederbehovTable.dialogId],
)
