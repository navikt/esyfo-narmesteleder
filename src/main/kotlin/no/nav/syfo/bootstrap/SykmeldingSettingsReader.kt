package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.platform.config.flag
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

internal fun ApplicationConfig.sykmeldingSettings() = SykmeldingKafkaSettings(
    persistSendtSykmelding = flag("sykmelding.persistSendtSykmelding"),
)
