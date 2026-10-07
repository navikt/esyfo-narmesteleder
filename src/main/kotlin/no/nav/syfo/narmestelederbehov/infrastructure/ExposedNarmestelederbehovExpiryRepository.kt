package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpiryRepository
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate

class ExposedNarmestelederbehovExpiryRepository(private val database: Database) : NarmestelederbehovExpiryRepository {
    override suspend fun expireBehov(sykmeldingMaxDate: LocalDate, limit: Int): Int = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
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
                .where { (SendtSykmeldingTomTable.tom less sykmeldingMaxDate) and (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses) }
                .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(limit)
                .map { it[NarmestelederbehovTable.id] }

            if (ids.isEmpty()) {
                0
            } else {
                NarmestelederbehovTable.update({
                    (NarmestelederbehovTable.id inList ids) and (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses)
                }) {
                    it[behovStatus] = BehovStatus.BEHOV_EXPIRED
                }
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
