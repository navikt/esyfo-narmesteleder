package no.nav.syfo.platform.kafka

import no.nav.syfo.bootstrap.localApplicationSettings

fun localKafkaSettings(): KafkaSettings = localApplicationSettings().kafka
