package no.nav.syfo.sykmelding.service

import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: ISykmeldingDb,
    private val otherEnvironmentProperties: OtherEnvironmentProperties,
) {
    suspend fun insertOrUpdateSykmelding(
        sendtSykmelding: SendtSykmeldingKafkaMessage
    ) {
        val hasEmployer = sendtSykmelding.event.arbeidsgiver != null
        val latestPeriodOrNull = sendtSykmelding.sykmelding.sykmeldingsperioder
            .maxBy { it.tom }
            .takeIf { period ->
                val today = LocalDate.now()
                // Vi har p.t. behandlingsgrunnlag for å be om nærmeste leder på sykmeldinger som er utløpt med inntil 16 dager.
                // Setter denne som miljøvariabel for testing utenfor prod
                today <= period.tom.plusDays(otherEnvironmentProperties.sykmeldingTomPaddingDays)
            }

        if (hasEmployer && latestPeriodOrNull != null) {
            val sykmeldingId = sendtSykmelding.event.sykmeldingId
            sykmeldingDb.insertOrUpdateSykmelding(
                SendtSykmeldingEntity(
                    sykmeldingId = UUID.fromString(sykmeldingId),
                    fnr = sendtSykmelding.kafkaMetadata.fnr,
                    orgnummer = sendtSykmelding.event.arbeidsgiver.orgnummer,
                    fom = latestPeriodOrNull.fom,
                    tom = latestPeriodOrNull.tom,
                    syketilfelleStartDato = sendtSykmelding.sykmelding.syketilfelleStartDato,
                )
            )
            logger.info("Inserted/updated sykmeldingId: $sykmeldingId")
        }
    }

    suspend fun revokeSykmelding(sykmeldingId: UUID) = sykmeldingDb.revokeSykmelding(sykmeldingId, LocalDate.now()).also {
        logger.info("Marked sykmeldingId: $sykmeldingId as revoked")
    }

    companion object {
        private val logger = logger()
    }
}
