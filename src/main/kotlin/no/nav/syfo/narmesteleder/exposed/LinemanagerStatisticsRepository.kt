package no.nav.syfo.narmesteleder.exposed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

interface ILinemanagerStatisticsRepository {
    suspend fun getStatistics(orgNumber: OrganizationNumber): LinemanagerStatistics
}

class LinemanagerStatisticsRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : ILinemanagerStatisticsRepository {
    override suspend fun getStatistics(orgNumber: OrganizationNumber): LinemanagerStatistics {
        val now = OffsetDateTime.now(clock)
        val activeLinemanager = activeLinemanagerCondition(orgNumber, now)
        val activeSykmelding = activeSykmeldingQuery(now.toLocalDate())

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                LinemanagerStatistics(
                    employeesOnSickLeaveWithoutLinemanager = NlBehovTable.countEmployeesWithoutLinemanager(orgNumber),
                    employeesOnSickLeaveWithLinemanager = NarmestelederTable.countEmployees(activeLinemanager and exists(activeSykmelding)),
                    employeesNotOnSickLeaveWithLinemanager = NarmestelederTable.countEmployees(activeLinemanager and notExists(activeSykmelding)),
                )
            }
        }
    }

    private fun activeLinemanagerCondition(
        orgNumber: OrganizationNumber,
        now: OffsetDateTime,
    ): Op<Boolean> = (NarmestelederTable.orgnummer eq orgNumber.value) and
        NarmestelederTable.aktivTom.isNull() and
        (NarmestelederTable.aktivFom lessEq now)

    private fun activeSykmeldingQuery(today: LocalDate) = SendtSykmeldingTable
        .select(SendtSykmeldingTable.id)
        .where {
            (SendtSykmeldingTable.fnr eq NarmestelederTable.sykmeldtFnr) and
                (SendtSykmeldingTable.orgnummer eq NarmestelederTable.orgnummer) and
                (SendtSykmeldingTable.tom greaterEq today) and
                (SendtSykmeldingTable.revokedDate.isNull() or (SendtSykmeldingTable.revokedDate greaterEq today))
        }

    private fun NlBehovTable.countEmployeesWithoutLinemanager(orgNumber: OrganizationNumber): Long {
        val count = sykmeldtFnr.countDistinct()
        return select(count)
            .where {
                (orgnummer eq orgNumber.value) and
                    (behovStatus inList OPEN_BEHOV_STATUSES)
            }.single()[count]
    }

    private fun NarmestelederTable.countEmployees(condition: Op<Boolean>): Long {
        val count = sykmeldtFnr.countDistinct()
        return select(count)
            .where(condition)
            .single()[count]
    }
}

private val OPEN_BEHOV_STATUSES = listOf(
    BehovStatus.BEHOV_CREATED,
    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
)
