package no.nav.syfo.sykmelding.retention.infrastructure

import no.nav.syfo.platform.database.ioTransaction
import no.nav.syfo.sykmelding.infrastructure.SendtSykmeldingTable
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import java.time.LocalDate

class ExposedSykmeldingRetentionRepository(
    private val database: Database,
) : SykmeldingRetentionRepository {
    override suspend fun deleteBefore(cutoff: LocalDate, batchSize: Int): Int {
        require(batchSize > 0) { "batchSize must be positive" }

        return database.ioTransaction {
            val ids = SendtSykmeldingTable
                .select(SendtSykmeldingTable.id)
                .where { SendtSykmeldingTable.tom less cutoff }
                .orderBy(SendtSykmeldingTable.id to SortOrder.ASC)
                .limit(batchSize)
                .map { it[SendtSykmeldingTable.id] }

            if (ids.isEmpty()) {
                0
            } else {
                SendtSykmeldingTable.deleteWhere {
                    (SendtSykmeldingTable.id inList ids) and (SendtSykmeldingTable.tom less cutoff)
                }
            }
        }
    }
}
