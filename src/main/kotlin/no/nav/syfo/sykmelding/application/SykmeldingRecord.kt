package no.nav.syfo.sykmelding.application

import java.util.UUID

data class SykmeldingRecord(
    val offset: Long,
    val sykmeldingId: UUID,
    val message: SendtSykmeldingKafkaMessage?
)
