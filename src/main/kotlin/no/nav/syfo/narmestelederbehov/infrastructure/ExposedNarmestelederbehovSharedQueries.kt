package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select

internal suspend fun Database.findOpenNarmestelederbehovFor(employee: Employee): List<NarmestelederbehovId> = ioTransaction {
    NarmestelederbehovTable.select(NarmestelederbehovTable.id)
        .where {
            (NarmestelederbehovTable.sykmeldtFnr eq employee.personIdent.value) and
                (NarmestelederbehovTable.orgnummer eq employee.organizationNumber.value) and
                (NarmestelederbehovTable.behovStatus inList openNarmestelederbehovStatuses)
        }
        .map { NarmestelederbehovId(it[NarmestelederbehovTable.id]) }
}
