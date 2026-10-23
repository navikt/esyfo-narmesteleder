package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeNameRepository
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

class ExposedNarmestelederbehovReadRepository(
    private val database: Database,
) : GetNarmestelederbehovRepository,
    NarmestelederbehovEmployeeNameRepository,
    ListNarmestelederbehovRepository {
    override suspend fun findDetails(id: NarmestelederbehovId): NarmestelederbehovDetails? = database.ioTransaction {
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
        ).where { NarmestelederbehovTable.id eq id.value }.singleOrNull()?.toNarmestelederbehovDetails()
    }

    override suspend fun findOpen(
        organizationNumber: OrganizationNumber,
        createdAfter: Instant,
        limit: Int,
    ): List<NarmestelederbehovDetails> = database.ioTransaction {
        NarmestelederbehovTable.selectAll()
            .where { isOpen(organizationNumber, createdAfter) }
            .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
            .limit(limit)
            .map { it.toNarmestelederbehovDetails() }
    }

    override suspend fun countOpen(
        organizationNumber: OrganizationNumber,
        createdAfter: Instant,
    ): Long = database.ioTransaction {
        NarmestelederbehovTable.selectAll().where { isOpen(organizationNumber, createdAfter) }.count()
    }

    override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
        database.ioTransaction {
            NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                it[fornavn] = name.firstName
                it[mellomnavn] = name.middleName
                it[etternavn] = name.lastName
            }
        }
    }
}

private fun isOpen(
    organizationNumber: OrganizationNumber,
    createdAfter: Instant,
): Op<Boolean> = (NarmestelederbehovTable.orgnummer eq organizationNumber.value) and
    (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses) and
    (NarmestelederbehovTable.created greater createdAfter.atOffset(ZoneOffset.UTC))
