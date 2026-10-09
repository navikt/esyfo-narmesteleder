package no.nav.syfo.narmestelederrelasjon.application

import java.time.Duration

enum class CheckOutcome { VALID, WOULD_REVOKE, FAILED, CLAIM_LOST, TIMEOUT }

enum class EmploymentComparisonResult { AGREE, DISAGREE }

enum class SourceObservationOutcome { RECORDED, ALREADY_RECORDED, IGNORED_STALE, FAILED }

internal val SOURCE_OBSERVATION_WINDOW: Duration = Duration.ofDays(7)
