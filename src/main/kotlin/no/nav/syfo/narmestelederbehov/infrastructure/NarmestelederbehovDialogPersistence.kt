package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.UUID

internal fun JdbcTransaction.recordCreatedDialog(
    id: NarmestelederbehovId,
    dialogId: UUID,
    name: BehovPersonName?,
    scheduleDispatch: JdbcTransaction.(UUID) -> Unit = JdbcTransaction::scheduleDialogDispatch,
) {
    NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
        it[NarmestelederbehovTable.dialogId] = dialogId
        it[fornavn] = name?.firstName
        it[mellomnavn] = name?.middleName
        it[etternavn] = name?.lastName
    }
    NarmestelederbehovTable.update({
        (NarmestelederbehovTable.id eq id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_CREATED)
    }) {
        it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
    }
    // A late CREATE must be reconciled against any fulfillment or expiry committed while it was in flight.
    scheduleDispatch(id.value)
}

internal fun JdbcTransaction.recordCompletedDialog(id: NarmestelederbehovId): Boolean = NarmestelederbehovTable.update({
    (NarmestelederbehovTable.id eq id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED)
}) {
    it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
} == 1

internal fun JdbcTransaction.recordExpiredDialog(id: NarmestelederbehovId, expiry: Instant) {
    NarmestelederbehovTable.update({
        (NarmestelederbehovTable.id eq id.value) and
            (NarmestelederbehovTable.behovStatus inList listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR))
    }) {
        it[expiredInDialogporten] = expiry.atOffset(UTC)
    }
}
