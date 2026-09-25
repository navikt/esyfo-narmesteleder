package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning

class ExposedNarmestelederbehovRepository(private val database: Database) : NarmestelederbehovRepository {
    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable
                .select(
                    NarmestelederbehovTable.id,
                    NarmestelederbehovTable.orgnummer,
                    NarmestelederbehovTable.sykmeldtFnr,
                )
                .where { NarmestelederbehovTable.id eq id.value }
                .singleOrNull()
                ?.let { row ->
                    Narmestelederbehov(
                        id = NarmestelederbehovId(row[NarmestelederbehovTable.id]),
                        employee = Employee(
                            PersonIdent(row[NarmestelederbehovTable.sykmeldtFnr]),
                            OrganizationNumber(row[NarmestelederbehovTable.orgnummer]),
                        ),
                    )
                }
        }
    }

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.updateReturning(
                returning = listOf(NarmestelederbehovTable.dialogId),
                where = { NarmestelederbehovTable.id eq id.value },
            ) {
                it[behovStatus] = BehovStatus.BEHOV_FULFILLED
            }.singleOrNull()?.let { MarkFulfilledResult.Marked(id, it[NarmestelederbehovTable.dialogId]) }
                ?: MarkFulfilledResult.Missing
        }
    }

    override suspend fun markDialogCompleted(id: NarmestelederbehovId) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                val updated = NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                    it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
                }
                check(updated == 1) { "Could not persist completed Dialogporten status for behov" }
            }
        }
    }
}
