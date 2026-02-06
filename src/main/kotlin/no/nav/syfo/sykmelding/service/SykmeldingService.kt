package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: ISykmeldingDb,
) {
    suspend fun insertOrUpdateSykmelding(
        sendtSykmelding: SendtSykmeldingKafkaMessage
    ) {
        val arbeidsgiver = sendtSykmelding.event.arbeidsgiver ?: return
        val sykmeldingId = sendtSykmelding.event.sykmeldingId
        val latestPeriod = sendtSykmelding.sykmelding.sykmeldingsperioder
            .maxBy { it.tom }

        if (latestPeriodIsWithinOneYear(latestPeriod)) {
            sykmeldingDb.insertOrUpdateSykmelding(
                SendtSykmeldingEntity(
                    sykmeldingId = UUID.fromString(sykmeldingId),
                    fnr = sendtSykmelding.kafkaMetadata.fnr,
                    orgnummer = arbeidsgiver.orgnummer,
                    fom = latestPeriod.fom,
                    tom = latestPeriod.tom,
                    syketilfelleStartDato = sendtSykmelding.sykmelding.syketilfelleStartDato,
                )
            )
            logger.info("Inserted/updated sykmeldingId: $sykmeldingId")
        }
    }

    private fun latestPeriodIsWithinOneYear(
        sykmeldingsperiodeAGDTO: SykmeldingsperiodeAGDTO
    ): Boolean = sykmeldingsperiodeAGDTO.tom >= LocalDate.now().minusYears(1)

    suspend fun revokeSykmelding(sykmeldingId: UUID) = sykmeldingDb.revokeSykmelding(sykmeldingId, LocalDate.now()).also {
        logger.info("Marked sykmeldingId: $sykmeldingId as revoked")
    }

    companion object {
        private val logger = logger()
    }
}
