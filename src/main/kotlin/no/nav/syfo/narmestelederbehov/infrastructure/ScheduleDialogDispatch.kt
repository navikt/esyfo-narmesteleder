package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZoneParam
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/** Call after a relevant behov change, using the writer's existing transaction and row lock. */
internal fun JdbcTransaction.scheduleDialogDispatch(behovId: UUID) {
    val behov = NarmestelederbehovTable
        .select(NarmestelederbehovTable.behovStatus, NarmestelederbehovTable.dialogId, NarmestelederbehovTable.created)
        .where { NarmestelederbehovTable.id eq behovId }
        .singleOrNull() ?: return
    with(DialogDispatchTable) {
        if (behov[NarmestelederbehovTable.behovStatus] != BehovStatus.BEHOV_CREATED &&
            behov[NarmestelederbehovTable.dialogId] == null &&
            select(this.behovId).where { this.behovId eq behovId }.empty()
        ) {
            return
        }
        val now = CustomFunction("clock_timestamp", availableAt.columnType)
        upsert(
            this.behovId,
            onUpdate = {
                it[revision] = revision + 1
                it[status] = Case()
                    .When(status eq DialogDispatchStatus.CLAIMED, LiteralOp(status.columnType, DialogDispatchStatus.CLAIMED))
                    .Else(LiteralOp(status.columnType, DialogDispatchStatus.READY))
                it[availableAt] = Case().When(status eq DialogDispatchStatus.CLAIMED, availableAt).Else(now)
                it[pendingSince] = Coalesce(pendingSince, now)
                it[attempts] = 0
                it[lastFailure] = null
            },
        ) {
            it[this.behovId] = behovId
            it[availableAt] = CustomFunction(
                "GREATEST",
                availableAt.columnType,
                now,
                timestampWithTimeZoneParam(behov[NarmestelederbehovTable.created].plusSeconds(10)),
            )
        }
    }
}
