package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.NameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.isAccepted
import java.util.concurrent.ConcurrentHashMap

private const val PARALLEL_NAMES_VALIDATION_TOTAL = "${METRICS_NS}_parallel_names_validation_total"
private const val PARALLEL_NAMES_VALIDATION_DESCRIPTION = "Counts parallel names validation attempts and outcomes."
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"
private const val NAME_VALIDATION_TOTAL = "${METRICS_NS}_name_validation_total"
private const val NAME_VALIDATION_DESCRIPTION = "Counts last name validation outcomes."
private const val FUZZY_SCORE = "${METRICS_NS}_name_validation_fuzzy_score"
private const val FUZZY_SCORE_DESCRIPTION =
    "Jaro-Winkler scores after exact and orthographic variant last name matches fail."
private const val MATCH_TYPE_TAG = "match_type"
private const val NAME_SOURCE_TAG = "name_source"
private const val VALIDATION_RESULT_TAG = "validation_result"
private const val NAME_SOURCE_SINGLE = "single"
private const val NAME_SOURCE_PARALLEL = "parallel"
private const val VALIDATION_RESULT_ACCEPTED = "accepted"
private const val VALIDATION_RESULT_REJECTED = "rejected"

private val parallelNamesValidationCounters: Map<String, Counter> = listOf(
    RESULT_ATTEMPTED,
    RESULT_SUCCESS,
    RESULT_FAILED,
).associateWith { result ->
    Counter.builder(PARALLEL_NAMES_VALIDATION_TOTAL)
        .description(PARALLEL_NAMES_VALIDATION_DESCRIPTION)
        .tag(RESULT_TAG, result)
        .register(METRICS_REGISTRY)
}
private val nameValidationCounters = ConcurrentHashMap<NameValidationMetricKey, Counter>()
private val fuzzyScoreSummaries = ConcurrentHashMap<String, DistributionSummary>()

class MicrometerNameValidationMetrics : NameValidationMetrics {
    override fun record(match: LastNameMatch) {
        val source = if (match.hasParallelNames) NAME_SOURCE_PARALLEL else NAME_SOURCE_SINGLE
        val accepted = match.isAccepted()
        countNameValidation(match.metricValue(), source, accepted)
        if (match.hasParallelNames) {
            countParallelNamesValidation(RESULT_ATTEMPTED)
            countParallelNamesValidation(if (accepted) RESULT_SUCCESS else RESULT_FAILED)
        }
        match.fuzzyScore()?.let { countFuzzyScore(source, it) }
    }
}

private data class NameValidationMetricKey(
    val matchType: String,
    val nameSource: String,
    val validationResult: String,
)

private fun LastNameMatch.metricValue(): String = when (this) {
    is LastNameMatch.Exact -> "exact"
    is LastNameMatch.OrthographicVariant -> "orthographic_variant"
    is LastNameMatch.Fuzzy -> "fuzzy"
    is LastNameMatch.NoMatch -> "none"
}

private fun LastNameMatch.fuzzyScore(): Double? = when (this) {
    is LastNameMatch.Fuzzy -> score
    is LastNameMatch.NoMatch -> bestFuzzyScore
    is LastNameMatch.Exact, is LastNameMatch.OrthographicVariant -> null
}

private fun countParallelNamesValidation(result: String) {
    parallelNamesValidationCounters.getValue(result).increment()
}

private fun countNameValidation(matchType: String, nameSource: String, isAccepted: Boolean) {
    val key = NameValidationMetricKey(
        matchType = matchType,
        nameSource = nameSource,
        validationResult = if (isAccepted) VALIDATION_RESULT_ACCEPTED else VALIDATION_RESULT_REJECTED,
    )
    nameValidationCounters.computeIfAbsent(key) {
        Counter.builder(NAME_VALIDATION_TOTAL)
            .description(NAME_VALIDATION_DESCRIPTION)
            .tag(MATCH_TYPE_TAG, key.matchType)
            .tag(NAME_SOURCE_TAG, key.nameSource)
            .tag(VALIDATION_RESULT_TAG, key.validationResult)
            .register(METRICS_REGISTRY)
    }.increment()
}

private fun countFuzzyScore(nameSource: String, score: Double) {
    fuzzyScoreSummaries.computeIfAbsent(nameSource) {
        DistributionSummary.builder(FUZZY_SCORE)
            .description(FUZZY_SCORE_DESCRIPTION)
            .tag(NAME_SOURCE_TAG, nameSource)
            .register(METRICS_REGISTRY)
    }.record(score)
}
