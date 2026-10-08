package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.Instant
import java.time.ZoneOffset

class ExposedOpenNarmestelederbehovRepository(private val database: Database) : OpenNarmestelederbehovRepository {
    override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.selectAll()
                .where { isOpen(organizationNumber, createdAfter) }
                .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(limit)
                .map { it.toNarmestelederbehovDetails() }
        }
    }

    override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.selectAll().where { isOpen(organizationNumber, createdAfter) }.count()
        }
    }
}

private fun isOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Op<Boolean> = (NarmestelederbehovTable.orgnummer eq organizationNumber.value) and
    (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses) and
    (NarmestelederbehovTable.created greater createdAfter.atOffset(ZoneOffset.UTC))
