package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.CompleteFulfilledNarmestelederbehovDialogsRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogReference
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Instant
import java.time.ZoneOffset

class ExposedNarmestelederbehovFulfillmentRepository(
    private val database: Database,
) : FulfillNarmestelederbehovRepository,
    FulfillNarmestelederbehovFromLeesahRepository,
    CompleteFulfilledNarmestelederbehovDialogsRepository {
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

    override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = database.ioTransaction {
        NarmestelederbehovTable.updateReturning(
            returning = listOf(NarmestelederbehovTable.dialogId),
            where = { NarmestelederbehovTable.id eq id.value },
        ) {
            it[behovStatus] = BehovStatus.BEHOV_FULFILLED
        }.singleOrNull()?.let { MarkFulfilledResult.Marked(id, it[NarmestelederbehovTable.dialogId]) }
            ?: MarkFulfilledResult.Missing
    }

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = database.ioTransaction {
        val updated = NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED)
        }) {
            it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
        }
        if (updated == 1) MarkDialogCompletedResult.Marked else MarkDialogCompletedResult.NotFulfilled
    }

    override suspend fun findFulfilledDialogs(createdBefore: Instant): List<NarmestelederbehovDialogReference> = database.ioTransaction {
        NarmestelederbehovTable.select(NarmestelederbehovTable.id, NarmestelederbehovTable.dialogId)
            .where {
                (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED) and
                    (NarmestelederbehovTable.created less createdBefore.atOffset(ZoneOffset.UTC)) and
                    NarmestelederbehovTable.dialogId.isNotNull()
            }
            .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
            .limit(100)
            .map { NarmestelederbehovDialogReference(NarmestelederbehovId(it[NarmestelederbehovTable.id]), requireNotNull(it[NarmestelederbehovTable.dialogId])) }
    }
}
