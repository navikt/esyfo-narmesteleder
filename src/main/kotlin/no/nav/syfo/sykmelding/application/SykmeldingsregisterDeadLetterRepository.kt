package no.nav.syfo.sykmelding.application

import java.util.UUID

enum class SykmeldingsregisterRejection {
    MISSING_KEY,
    INVALID_KEY,
    MALFORMED_PAYLOAD,
    INVALID_PAYLOAD,
}

/**
 * A record that can never be stored. [fieldPath] names the failing field path, never its value, and
 * [sykmeldingId] is null when the key is missing or invalid.
 */
data class SykmeldingsregisterDeadLetter(
    val position: SykmeldingsregisterPosition,
    val sykmeldingId: UUID?,
    val rejection: SykmeldingsregisterRejection,
    val fieldPath: String? = null,
) {
    val reason: String
        get() = fieldPath?.let { "${rejection.name}: $it" } ?: rejection.name
}

interface SykmeldingsregisterDeadLetterRepository {
    /** Saves each dead letter once and returns those stored now; a redelivered record is ignored. */
    suspend fun saveAll(deadLetters: List<SykmeldingsregisterDeadLetter>): List<SykmeldingsregisterDeadLetter>
}
