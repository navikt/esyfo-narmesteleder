package no.nav.syfo.sykmelding.application

import java.util.UUID

interface ActiveSykmeldingRepository {
    suspend fun findActiveSykmelding(fnr: String, orgnummer: String): LocalActiveSykmeldingResult
}

class LocalActiveSykmeldingResult(
    val isActive: Boolean,
    /** Internal value for controlled troubleshooting; must never be logged or used as a standard log correlation ID. */
    val sykmeldingId: UUID?,
) {
    override fun toString(): String = "LocalActiveSykmeldingResult(isActive=$isActive, sykmeldingId=<redacted>)"
}
