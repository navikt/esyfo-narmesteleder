package no.nav.syfo.platform.kafka

import no.nav.syfo.bootstrap.ApplicationSettings
import no.nav.syfo.platform.config.localApplicationConfig

fun localKafkaSettings(): KafkaSettings = ApplicationSettings.from(localApplicationConfig()).kafka
