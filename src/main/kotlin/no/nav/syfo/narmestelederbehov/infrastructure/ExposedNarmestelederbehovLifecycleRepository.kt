package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.DialogScanPosition
import no.nav.syfo.narmestelederbehov.application.ExpirableNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovDialogsRepository
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.MarkDialogCreatedResult
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.PendingNarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.SendPendingNarmestelederbehovDialogsRepository
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.andIfNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ExposedNarmestelederbehovLifecycleRepository(
    private val database: Database,
) : CreateNarmestelederbehovRepository,
    SendPendingNarmestelederbehovDialogsRepository,
    ExpireNarmestelederbehovRepository,
    ExpireNarmestelederbehovDialogsRepository {
    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = database.findOpenNarmestelederbehovFor(employee)

    override suspend fun findPendingDialogs(after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<PendingNarmestelederbehovDialog> = database.ioTransaction {
        NarmestelederbehovTable.select(
            NarmestelederbehovTable.id,
            NarmestelederbehovTable.sykmeldtFnr,
            NarmestelederbehovTable.orgnummer,
            NarmestelederbehovTable.created,
            NarmestelederbehovTable.dialogId,
        ).scanPage(
            eligible = NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_CREATED,
            after = after,
            highWaterMark = highWaterMark,
            limit = limit,
        ).map { row ->
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

    override suspend fun findDialogsToExpire(after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<ExpirableNarmestelederbehovDialog> = database.ioTransaction {
        NarmestelederbehovTable.select(NarmestelederbehovTable.id, NarmestelederbehovTable.dialogId, NarmestelederbehovTable.created)
            .scanPage(
                eligible = (NarmestelederbehovTable.behovStatus inList listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR)) and
                    NarmestelederbehovTable.expiredInDialogporten.isNull() and
                    NarmestelederbehovTable.dialogId.isNotNull(),
                after = after,
                highWaterMark = highWaterMark,
                limit = limit,
            )
            .map {
                ExpirableNarmestelederbehovDialog(
                    id = NarmestelederbehovId(it[NarmestelederbehovTable.id]),
                    dialogId = requireNotNull(it[NarmestelederbehovTable.dialogId]),
                    created = it[NarmestelederbehovTable.created].toInstant(),
                )
            }
    }

    /**
     * Narrows the query to one page of a scan cycle: the first [limit] behov matching [eligible] that were
     * created before [highWaterMark] and come strictly after [after] in (created, id) order.
     */
    private fun Query.scanPage(eligible: Op<Boolean>, after: DialogScanPosition?, highWaterMark: Instant, limit: Int): Query {
        val beforeHighWaterMark = NarmestelederbehovTable.created less highWaterMark.atOffset(ZoneOffset.UTC)
        return where((eligible and beforeHighWaterMark) andIfNotNull keysetAfter(after))
            .orderBy(NarmestelederbehovTable.created to SortOrder.ASC, NarmestelederbehovTable.id to SortOrder.ASC)
            .limit(limit)
    }

    /** Keyset predicate that continues strictly after [after] in (created, id) order; null means from the start. */
    private fun keysetAfter(after: DialogScanPosition?): Op<Boolean>? = if (after == null) {
        null
    } else {
        val afterCreated = after.created.atOffset(ZoneOffset.UTC)
        // Implied by the OR below; repeated as a plain range so PostgreSQL can use it as an index bound on created.
        (NarmestelederbehovTable.created greaterEq afterCreated) and
            (
                (NarmestelederbehovTable.created greater afterCreated) or
                    ((NarmestelederbehovTable.created eq afterCreated) and (NarmestelederbehovTable.id greater after.id.value))
                )
    }

    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) {
        database.ioTransaction {
            NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                it[expiredInDialogporten] = expirationTime.atOffset(ZoneOffset.UTC)
            }
        }
    }

    override suspend fun markDialogCreated(
        id: NarmestelederbehovId,
        dialogId: UUID,
        name: BehovPersonName?,
    ): MarkDialogCreatedResult = database.ioTransaction {
        val updated = NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_CREATED)
        }) {
            it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
            it[NarmestelederbehovTable.dialogId] = dialogId
            it[fornavn] = name?.firstName
            it[mellomnavn] = name?.middleName
            it[etternavn] = name?.lastName
        }
        if (updated == 1) MarkDialogCreatedResult.Marked else MarkDialogCreatedResult.NotCreated
    }

    // The partial unique index on open behov is the only unique constraint besides the generated id, so an ignored insert means an open behov already exists.
    override suspend fun create(behov: NewNarmestelederbehov): CreateBehovResult = database.ioTransaction {
        NarmestelederbehovTable.insertReturning(returning = listOf(NarmestelederbehovTable.id), ignoreErrors = true) {
            it[orgnummer] = behov.employee.organizationNumber.value
            it[hovedenhetOrgnummer] = behov.mainOrganizationNumber
            it[sykmeldtFnr] = behov.employee.personIdent.value
            it[narmestelederFnr] = behov.manager?.value
            it[behovReason] = behov.reason.name
            it[behovStatus] = behov.status
            it[avbruttNarmestelederId] = behov.revokedRelationId
        }.singleOrNull()
            ?.let { CreateBehovResult.Created(NarmestelederbehovId(it[NarmestelederbehovTable.id])) }
            ?: CreateBehovResult.AlreadyExists
    }

    override suspend fun expireBehov(sykmeldingMaxDate: LocalDate, limit: Int): Int = database.ioTransaction {
        val ids = NarmestelederbehovTable
            .join(
                SendtSykmeldingTomTable,
                JoinType.INNER,
                additionalConstraint = {
                    (NarmestelederbehovTable.sykmeldtFnr eq SendtSykmeldingTomTable.fnr) and
                        (NarmestelederbehovTable.orgnummer eq SendtSykmeldingTomTable.orgnummer)
                },
            )
            .select(NarmestelederbehovTable.id)
            .where {
                (SendtSykmeldingTomTable.tom less sykmeldingMaxDate) and
                    (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses)
            }
            .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
            .limit(limit)
            .map { it[NarmestelederbehovTable.id] }

        if (ids.isEmpty()) {
            0
        } else {
            NarmestelederbehovTable.update({
                (NarmestelederbehovTable.id inList ids) and
                    (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses)
            }) {
                it[behovStatus] = BehovStatus.BEHOV_EXPIRED
            }
        }
    }
}

/** Read-only view of the `sendt_sykmelding` columns needed to expire behov. */
private object SendtSykmeldingTomTable : Table("sendt_sykmelding") {
    val fnr = text("fnr")
    val orgnummer = varchar("orgnummer", 9)
    val tom = date("tom")
}
