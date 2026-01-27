package no.nav.syfo.sykmelding.service

import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.model.SendtSykmeldingDto
import no.nav.syfo.sykmelding.model.toDbEntity
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: SykmeldingDb,
    private val env: OtherEnvironmentProperties
) {
    suspend fun insertOrUpdateSykmelding(
        sendtSykmelding: SendtSykmeldingDto
    ) = if (env.persistSendtSykmelding) {
        sykmeldingDb.insertSykmelding(sendtSykmelding.toDbEntity())
    } else {
        logger.info("Not persisting sendt sykmelding with id ${sendtSykmelding.sykmeldingId} due to configuration")
    }

    suspend fun revokeSykmelding(sykmeldingId: UUID) = if (env.persistSendtSykmelding) {
        sykmeldingDb.revokeSykmelding(sykmeldingId, LocalDate.now()).also { rows ->
            logger.info("Marked $rows entries with sykmeldingId: $sykmeldingId as revoked")
        }
    } else {
        logger.info("Wont revoke entries with sykmeldingId $sykmeldingId due to configuration")
    }

    companion object {
        private val logger = logger()
    }
}
