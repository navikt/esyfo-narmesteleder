package no.nav.syfo.sykmelding.service

import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.model.toDbEntity
import no.nav.syfo.util.logger
import java.util.UUID

class SykmeldingService(private val sykmeldingDb: SykmeldingDb, private val env: OtherEnvironmentProperties) {
    suspend fun insertSykmelding(
        sendtSykmelding: SendtSykmeldingKafkaMessage
    ) = if (env.persistSendtSykmelding) {
        sykmeldingDb.insertSykmelding(sendtSykmelding.toDbEntity())
    } else {
        logger.info("Not persisting sendt sykmelding with id ${sendtSykmelding.kafkaMetadata.sykmeldingId} due to configuration")
    }

    suspend fun deleteSykmelding(sykmeldingId: UUID) = if (env.persistSendtSykmelding) {
        sykmeldingDb.deleteSykmelding(sykmeldingId).also { rows ->
            logger.info("Deleted $rows entries with sykmeldingId: $sykmeldingId")
        }
        // TODO: Update expiration in Dialogporten
    } else {
        logger.info("Wont delete entries with sykmeldingId $sykmeldingId due to configuration")
    }

    companion object {
        private val logger = logger()
    }
}
