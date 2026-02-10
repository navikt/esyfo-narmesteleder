package no.nav.syfo.sykmelding.kafka

import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import java.util.UUID

data class SykmeldingRecord(
    val offset: Long,
    val sykmeldingId: UUID,
    val message: SendtSykmeldingKafkaMessage?
)
