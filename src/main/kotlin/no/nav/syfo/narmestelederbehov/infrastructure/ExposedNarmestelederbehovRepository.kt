package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.MarkDialogCompletedResult
import no.nav.syfo.narmestelederbehov.application.MarkFulfilledResult
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.Instant
import java.time.ZoneOffset

class ExposedNarmestelederbehovRepository(private val database: Database) :
    NarmestelederbehovRepository,
    OpenNarmestelederbehovRepository {
    override suspend fun findDetails(id: NarmestelederbehovId): NarmestelederbehovDetails? = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.select(
                NarmestelederbehovTable.id,
                NarmestelederbehovTable.orgnummer,
                NarmestelederbehovTable.hovedenhetOrgnummer,
                NarmestelederbehovTable.sykmeldtFnr,
                NarmestelederbehovTable.narmestelederFnr,
                NarmestelederbehovTable.fornavn,
                NarmestelederbehovTable.mellomnavn,
                NarmestelederbehovTable.etternavn,
                NarmestelederbehovTable.created,
                NarmestelederbehovTable.updated,
                NarmestelederbehovTable.behovStatus,
                NarmestelederbehovTable.behovReason,
            ).where { NarmestelederbehovTable.id eq id.value }.singleOrNull()?.toDetails()
        }
    }

    override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.selectAll()
                .where { isOpen(organizationNumber, createdAfter) }
                .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(limit)
                .map { it.toDetails() }
        }
    }

    override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.selectAll().where { isOpen(organizationNumber, createdAfter) }.count()
        }
    }

    override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                    it[fornavn] = name.firstName
                    it[mellomnavn] = name.middleName
                    it[etternavn] = name.lastName
                }
            }
        }
    }

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

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            val updated = NarmestelederbehovTable.update({
                (NarmestelederbehovTable.id eq id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED)
            }) {
                it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
            }
            if (updated == 1) MarkDialogCompletedResult.Marked else MarkDialogCompletedResult.NotFulfilled
        }
    }
}

private val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)

private fun isOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Op<Boolean> = (NarmestelederbehovTable.orgnummer eq organizationNumber.value) and
    (NarmestelederbehovTable.behovStatus inList openStatuses) and
    (NarmestelederbehovTable.created greater createdAfter.atOffset(ZoneOffset.UTC))

private fun ResultRow.toDetails() = NarmestelederbehovDetails(
    id = NarmestelederbehovId(this[NarmestelederbehovTable.id]),
    employeeIdent = PersonIdent(this[NarmestelederbehovTable.sykmeldtFnr]),
    organizationNumber = OrganizationNumber(this[NarmestelederbehovTable.orgnummer]),
    mainOrganizationNumber = requireNotNull(this[NarmestelederbehovTable.hovedenhetOrgnummer]),
    managerIdent = this[NarmestelederbehovTable.narmestelederFnr]?.let(::PersonIdent),
    firstName = this[NarmestelederbehovTable.fornavn],
    middleName = this[NarmestelederbehovTable.mellomnavn],
    lastName = this[NarmestelederbehovTable.etternavn],
    created = this[NarmestelederbehovTable.created].toInstant(),
    updated = this[NarmestelederbehovTable.updated].toInstant(),
    status = this[NarmestelederbehovTable.behovStatus],
    reason = BehovReason.valueOf(this[NarmestelederbehovTable.behovReason]),
)
