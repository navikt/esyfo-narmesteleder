package no.nav.syfo.narmestelederstatistikk.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederstatistikk.application.Narmestelederstatistikk
import no.nav.syfo.narmestelederstatistikk.application.NarmestelederstatistikkRepository
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
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
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.postgresql.util.PGobject
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

class ExposedNarmestelederstatistikkRepository(
    private val database: Database,
    private val clock: Clock = Clock.systemUTC(),
) : NarmestelederstatistikkRepository {
    override suspend fun countFor(organizationNumber: OrganizationNumber): Narmestelederstatistikk {
        val now = OffsetDateTime.now(clock)
        val activeNarmesteleder = activeNarmestelederIn(organizationNumber, now)
        val activeSykmelding = activeSykmeldingFor(now.toLocalDate())

        return withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                Narmestelederstatistikk(
                    employeesOnSickLeaveWithoutNarmesteleder = countEmployeesWithOpenBehov(organizationNumber),
                    employeesOnSickLeaveWithNarmesteleder = countEmployeesWithNarmesteleder(activeNarmesteleder and exists(activeSykmelding)),
                    employeesNotOnSickLeaveWithNarmesteleder = countEmployeesWithNarmesteleder(activeNarmesteleder and notExists(activeSykmelding)),
                )
            }
        }
    }

    private fun countEmployeesWithOpenBehov(organizationNumber: OrganizationNumber): Long {
        val count = NarmestelederbehovStatisticsTable.sykmeldtFnr.countDistinct()
        return NarmestelederbehovStatisticsTable.select(count)
            .where {
                (NarmestelederbehovStatisticsTable.orgnummer eq organizationNumber.value) and
                    (NarmestelederbehovStatisticsTable.behovStatus inList OPEN_BEHOV_STATUSES)
            }.single()[count]
    }

    private fun countEmployeesWithNarmesteleder(condition: Op<Boolean>): Long {
        val count = NarmestelederStatisticsTable.sykmeldtFnr.countDistinct()
        return NarmestelederStatisticsTable.select(count).where(condition).single()[count]
    }

    private fun activeNarmestelederIn(
        organizationNumber: OrganizationNumber,
        now: OffsetDateTime,
    ): Op<Boolean> = (NarmestelederStatisticsTable.orgnummer eq organizationNumber.value) and
        NarmestelederStatisticsTable.aktivTom.isNull() and
        (NarmestelederStatisticsTable.aktivFom lessEq now)

    private fun activeSykmeldingFor(today: LocalDate) = SendtSykmeldingStatisticsTable
        .select(SendtSykmeldingStatisticsTable.id)
        .where {
            (SendtSykmeldingStatisticsTable.fnr eq NarmestelederStatisticsTable.sykmeldtFnr) and
                (SendtSykmeldingStatisticsTable.orgnummer eq NarmestelederStatisticsTable.orgnummer) and
                (SendtSykmeldingStatisticsTable.tom greaterEq today) and
                (SendtSykmeldingStatisticsTable.revokedDate.isNull() or (SendtSykmeldingStatisticsTable.revokedDate greaterEq today))
        }
}

private const val BEHOV_CREATED = "BEHOV_CREATED"
private const val DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION = "DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION"
private val OPEN_BEHOV_STATUSES = listOf(BEHOV_CREATED, DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)

private object NarmestelederbehovStatisticsTable : Table("nl_behov") {
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykemeldt_fnr", 11)
    val behovStatus = registerColumn("behov_status", BehovStatusStringColumnType())
}

private object NarmestelederStatisticsTable : Table("narmeste_leder") {
    val orgnummer = varchar("orgnummer", 9)
    val sykmeldtFnr = varchar("sykmeldt_fnr", 11)
    val aktivFom = timestampWithTimeZone("aktiv_fom")
    val aktivTom = timestampWithTimeZone("aktiv_tom").nullable()
}

private object SendtSykmeldingStatisticsTable : Table("sendt_sykmelding") {
    val id = integer("id")
    val orgnummer = varchar("orgnummer", 9)
    val fnr = text("fnr")
    val tom = date("tom")
    val revokedDate = date("revoked_date").nullable()
}

private class BehovStatusStringColumnType : ColumnType<String>() {
    override fun sqlType(): String = "BEHOV_STATUS"

    override fun valueFromDB(value: Any): String = value.toString()

    override fun notNullValueToDB(value: String): Any = PGobject().apply {
        type = sqlType()
        this.value = value
    }

    override fun nonNullValueToString(value: String): String = "'${value.replace("'", "''")}'"
}
