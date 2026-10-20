package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.PendingNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.PendingNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import java.time.ZoneOffset

class ExposedPendingNarmestelederbehovRepository(private val database: Database) : PendingNarmestelederbehovRepository {
    override suspend fun findPendingDialogs(createdBefore: Instant, limit: Int): List<PendingNarmestelederbehovDialog> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.select(
                NarmestelederbehovTable.id,
                NarmestelederbehovTable.sykmeldtFnr,
                NarmestelederbehovTable.orgnummer,
                NarmestelederbehovTable.created,
                NarmestelederbehovTable.dialogId,
            ).where {
                (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_CREATED) and
                    (NarmestelederbehovTable.created less createdBefore.atOffset(ZoneOffset.UTC))
            }.orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(limit)
                .map { row ->
                    PendingNarmestelederbehovDialog(
                        id = NarmestelederbehovId(row[NarmestelederbehovTable.id]),
                        employee = Employee(
                            PersonalIdentificationNumber(row[NarmestelederbehovTable.sykmeldtFnr]),
                            OrganizationNumber(row[NarmestelederbehovTable.orgnummer]),
                        ),
                        created = row[NarmestelederbehovTable.created].toInstant(),
                        dialogId = row[NarmestelederbehovTable.dialogId],
                    )
                }
        }
    }
}
