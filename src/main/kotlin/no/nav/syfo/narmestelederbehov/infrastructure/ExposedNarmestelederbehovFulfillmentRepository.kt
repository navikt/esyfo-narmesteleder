package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.util.UUID

class ExposedNarmestelederbehovFulfillmentRepository(
    private val database: Database,
    private val scheduleDispatch: JdbcTransaction.(UUID) -> Unit = JdbcTransaction::scheduleDialogDispatch,
) : FulfillNarmestelederbehovRepository,
    FulfillNarmestelederbehovFromLeesahRepository {
    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = database.ioTransaction {
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
                        PersonalIdentificationNumber(row[NarmestelederbehovTable.sykmeldtFnr]),
                        OrganizationNumber(row[NarmestelederbehovTable.orgnummer]),
                    ),
                )
            }
    }

    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = database.findOpenNarmestelederbehovFor(employee)

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
        val row = NarmestelederbehovTable.select(NarmestelederbehovTable.behovStatus, NarmestelederbehovTable.dialogId)
            .where { NarmestelederbehovTable.id eq id.value }.forUpdate().singleOrNull()
            ?: return@ioTransaction MarkFulfilledResult.Missing
        if (row[NarmestelederbehovTable.behovStatus] != BehovStatus.BEHOV_FULFILLED) {
            NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                it[behovStatus] = BehovStatus.BEHOV_FULFILLED
            }
            scheduleDispatch(id.value)
        }
        MarkFulfilledResult.Marked(id, row[NarmestelederbehovTable.dialogId])
    }
}
