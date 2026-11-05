package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

data class NarmestelederrelasjonKafkaSettings(
    val persistRegister: Boolean,
    val pdlConsumerEnabled: Boolean,
)
