package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

class DbNarmestelederbehovRepository(private val db: NarmestelederDb) : NarmestelederbehovRepository {
    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = db.findBehovById(id.value)?.let {
        Narmestelederbehov(id, Employee(PersonIdent(it.sykmeldtFnr), OrganizationNumber(it.orgnummer)))
    }

    override suspend fun markFulfilled(id: NarmestelederbehovId) {
        // Re-read after publication, matching the legacy missing-behov failure.
        val behov = db.findBehovById(id.value)
            ?: throw ApiErrorException.NotFoundException("A LinemanagerRequirement was not found")
        db.updateNlBehov(behov.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))
    }
}
