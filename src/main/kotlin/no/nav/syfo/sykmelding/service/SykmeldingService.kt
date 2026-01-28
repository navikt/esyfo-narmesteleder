package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: SykmeldingDb,
) {
    suspend fun insertOrUpdateSykmelding(
        sendtSykmelding: SendtSykmeldingKafkaMessage
    ) {
        val hasEmployer = sendtSykmelding.event.arbeidsgiver != null
        if (hasEmployer) {
            val latestSykmeldingPeriod = sendtSykmelding.sykmelding.sykmeldingsperioder.maxBy { it.fom }
            sykmeldingDb.insertOrUpdateSykmelding(
                SendtSykmeldingEntity(
                    sykmeldingId = UUID.fromString(sendtSykmelding.event.sykmeldingId),
                    fnr = sendtSykmelding.kafkaMetadata.fnr,
                    orgnummer = sendtSykmelding.event.arbeidsgiver.orgnummer,
                    fom = latestSykmeldingPeriod.fom,
                    tom = latestSykmeldingPeriod.tom,
                    syketilfelleStartDato = sendtSykmelding.sykmelding.syketilfelleStartDato,
                )
            )
        }
    }

    suspend fun revokeSykmelding(sykmeldingId: UUID) = sykmeldingDb.revokeSykmelding(sykmeldingId, LocalDate.now()).also { rows ->
        logger.info("Marked sykmeldingId: $sykmeldingId as revoked")
    }

    companion object {
        private val logger = logger()
    }
}
