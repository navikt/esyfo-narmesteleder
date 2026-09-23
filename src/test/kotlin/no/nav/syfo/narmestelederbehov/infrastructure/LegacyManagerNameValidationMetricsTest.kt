package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch

class LegacyManagerNameValidationMetricsTest :
    FunSpec({
        val recorder = LegacyManagerNameValidationMetrics()
        val nameMetric = "${METRICS_NS}_name_validation_total"
        val parallelMetric = "${METRICS_NS}_parallel_names_validation_total"
        val fuzzyMetric = "${METRICS_NS}_name_validation_fuzzy_score"

        test("records rejected parallel-name fuzzy score and outcome using legacy meters") {
            val rejected = METRICS_REGISTRY.find(nameMetric)
                .tags("match_type", "none", "name_source", "parallel", "validation_result", "rejected")
            val attempted = METRICS_REGISTRY.find(parallelMetric).tag("result", "attempted")
            val failed = METRICS_REGISTRY.find(parallelMetric).tag("result", "failed")
            val score = METRICS_REGISTRY.find(fuzzyMetric).tag("name_source", "parallel")
            val beforeRejected = rejected.counter()?.count() ?: 0.0
            val beforeAttempted = attempted.counter()?.count() ?: 0.0
            val beforeFailed = failed.counter()?.count() ?: 0.0
            val beforeScores = score.summary()?.count() ?: 0L

            recorder.record(ManagerLastNameMatch.NoMatch(0.82, hasParallelNames = true))

            rejected.counter()?.count() shouldBe beforeRejected + 1.0
            attempted.counter()?.count() shouldBe beforeAttempted + 1.0
            failed.counter()?.count() shouldBe beforeFailed + 1.0
            score.summary()?.count() shouldBe beforeScores + 1
        }

        test("records accepted fuzzy score even if a later side effect fails") {
            val accepted = METRICS_REGISTRY.find(nameMetric)
                .tags("match_type", "fuzzy", "name_source", "single", "validation_result", "accepted")
            val score = METRICS_REGISTRY.find(fuzzyMetric).tag("name_source", "single")
            val beforeCount = accepted.counter()?.count() ?: 0.0
            val beforeScores = score.summary()?.count() ?: 0L

            recorder.record(ManagerLastNameMatch.Fuzzy(0.95, hasParallelNames = false))

            accepted.counter()?.count() shouldBe beforeCount + 1.0
            score.summary()?.count() shouldBe beforeScores + 1
        }
    })
