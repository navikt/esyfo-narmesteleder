package no.nav.syfo.bootstrap

import no.nav.syfo.platform.config.ConfigReader
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

internal fun ConfigReader.sykmeldingSettings() = SykmeldingKafkaSettings(
    persistSendtSykmelding = flag("sykmelding.persistSendtSykmelding"),
)
