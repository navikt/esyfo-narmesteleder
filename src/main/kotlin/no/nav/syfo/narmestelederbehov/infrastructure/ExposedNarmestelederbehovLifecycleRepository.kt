package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.application.CreateBehovResult
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.NewNarmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import java.time.LocalDate

class ExposedNarmestelederbehovLifecycleRepository(
    private val database: Database,
) : CreateNarmestelederbehovRepository,
    ExpireNarmestelederbehovRepository {
    override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = database.findOpenNarmestelederbehovFor(employee)

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
            ?.let {
                val id = it[NarmestelederbehovTable.id]
                scheduleDialogDispatch(id)
                CreateBehovResult.Created(NarmestelederbehovId(id))
            }
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
            val expired = NarmestelederbehovTable.updateReturning(
                returning = listOf(NarmestelederbehovTable.id),
                where = {
                    (NarmestelederbehovTable.id inList ids) and
                        (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses)
                },
            ) {
                it[behovStatus] = BehovStatus.BEHOV_EXPIRED
            }.map { it[NarmestelederbehovTable.id] }
            expired.forEach { scheduleDialogDispatch(it) }
            expired.size
        }
    }
}

/** Read-only view of the `sendt_sykmelding` columns needed to expire behov. */
private object SendtSykmeldingTomTable : Table("sendt_sykmelding") {
    val fnr = text("fnr")
    val orgnummer = varchar("orgnummer", 9)
    val tom = date("tom")
}
