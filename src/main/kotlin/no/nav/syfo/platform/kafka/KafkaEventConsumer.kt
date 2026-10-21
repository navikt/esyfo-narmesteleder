package no.nav.syfo.platform.kafka

internal enum class KafkaEventConsumer {
    PDL_LEESAH,
    NL_LEESAH,
    NL_REPLAY,
    SENT_SYKMELDING,
    SENT_SYKMELDING_PERSIST,
}
