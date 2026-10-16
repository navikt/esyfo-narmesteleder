package no.nav.syfo.narmestelederrelasjon.application

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Defaults to three minutes, the production Kafka bound, so lease arithmetic in tests matches production. */
internal class RecordingRevocationPublisher(
    override val maxPublishDuration: Duration = 3.minutes,
    private val onPublish: suspend (PublishNarmestelederrelasjonRevocationCommand) -> Unit,
) : PublishNarmestelederrelasjonRevocation {
    override suspend fun publish(command: PublishNarmestelederrelasjonRevocationCommand) = onPublish(command)
}
