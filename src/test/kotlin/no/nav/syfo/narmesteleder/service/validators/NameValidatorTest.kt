package no.nav.syfo.narmesteleder.service.validators

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

class NameValidatorTest :
    DescribeSpec({
        describe("name validation metrics") {
            it("records accepted fuzzy matches and their scores") {
                val before = nameValidationCount("fuzzy", NAME_SOURCE_SINGLE, "accepted")
                val scoresBefore = fuzzyScoreCount(NAME_SOURCE_SINGLE)

                NameValidator.recordManagerLastNameMatch(NameMatchType.FUZZY, false, 0.95)

                nameValidationCount("fuzzy", NAME_SOURCE_SINGLE, "accepted") shouldBeExactly before + 1.0
                fuzzyScoreCount(NAME_SOURCE_SINGLE) shouldBe scoresBefore + 1L
            }

            it("records accepted exact matches without a fuzzy score") {
                val before = nameValidationCount("exact", NAME_SOURCE_SINGLE, "accepted")
                val scoresBefore = fuzzyScoreCount(NAME_SOURCE_SINGLE)

                NameValidator.recordManagerLastNameMatch(NameMatchType.EXACT, false, null)

                nameValidationCount("exact", NAME_SOURCE_SINGLE, "accepted") shouldBeExactly before + 1.0
                fuzzyScoreCount(NAME_SOURCE_SINGLE) shouldBe scoresBefore
            }

            it("records accepted orthographic variants") {
                val before = nameValidationCount("orthographic_variant", NAME_SOURCE_SINGLE, "accepted")

                NameValidator.recordManagerLastNameMatch(NameMatchType.ORTHOGRAPHIC_VARIANT, false, null)

                nameValidationCount("orthographic_variant", NAME_SOURCE_SINGLE, "accepted") shouldBeExactly before + 1.0
            }

            it("counts an accepted fuzzy parallel name as successful") {
                val attemptedBefore = parallelNamesValidationCount(RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(RESULT_FAILED)
                val scoresBefore = fuzzyScoreCount(NAME_SOURCE_PARALLEL)

                NameValidator.recordManagerLastNameMatch(NameMatchType.FUZZY, true, 0.95)

                parallelNamesValidationCount(RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(RESULT_FAILED) shouldBeExactly failedBefore
                fuzzyScoreCount(NAME_SOURCE_PARALLEL) shouldBe scoresBefore + 1L
            }

            it("counts an accepted orthographic variant in parallel names as successful") {
                val nameValidationBefore = nameValidationCount("orthographic_variant", NAME_SOURCE_PARALLEL, "accepted")
                val attemptedBefore = parallelNamesValidationCount(RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(RESULT_FAILED)

                NameValidator.recordManagerLastNameMatch(NameMatchType.ORTHOGRAPHIC_VARIANT, true, null)

                nameValidationCount("orthographic_variant", NAME_SOURCE_PARALLEL, "accepted") shouldBeExactly nameValidationBefore + 1.0
                parallelNamesValidationCount(RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("counts rejected parallel names without marking success and records their fuzzy score") {
                val attemptedBefore = parallelNamesValidationCount(RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(RESULT_FAILED)
                val rejectedBefore = nameValidationCount("none", NAME_SOURCE_PARALLEL, "rejected")
                val scoresBefore = fuzzyScoreCount(NAME_SOURCE_PARALLEL)

                NameValidator.recordManagerLastNameMatch(NameMatchType.NONE, true, 0.82)

                parallelNamesValidationCount(RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(RESULT_SUCCESS) shouldBeExactly successBefore
                parallelNamesValidationCount(RESULT_FAILED) shouldBeExactly failedBefore + 1.0
                nameValidationCount("none", NAME_SOURCE_PARALLEL, "rejected") shouldBeExactly rejectedBefore + 1.0
                fuzzyScoreCount(NAME_SOURCE_PARALLEL) shouldBe scoresBefore + 1L
            }
        }
    })

private const val PARALLEL_NAMES_VALIDATION_TOTAL = "${METRICS_NS}_parallel_names_validation_total"
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"
private const val NAME_VALIDATION_TOTAL = "${METRICS_NS}_name_validation_total"
private const val FUZZY_SCORE = "${METRICS_NS}_name_validation_fuzzy_score"
private const val MATCH_TYPE_TAG = "match_type"
private const val NAME_SOURCE_TAG = "name_source"
private const val VALIDATION_RESULT_TAG = "validation_result"
private const val NAME_SOURCE_SINGLE = "single"
private const val NAME_SOURCE_PARALLEL = "parallel"

private fun parallelNamesValidationCount(result: String): Double = METRICS_REGISTRY.find(PARALLEL_NAMES_VALIDATION_TOTAL)
    .tag(RESULT_TAG, result)
    .counter()
    ?.count() ?: 0.0

private fun nameValidationCount(
    matchType: String,
    nameSource: String,
    validationResult: String,
): Double = METRICS_REGISTRY.find(NAME_VALIDATION_TOTAL)
    .tags(MATCH_TYPE_TAG, matchType, NAME_SOURCE_TAG, nameSource, VALIDATION_RESULT_TAG, validationResult)
    .counter()
    ?.count() ?: 0.0

private fun fuzzyScoreCount(nameSource: String): Long = METRICS_REGISTRY.find(FUZZY_SCORE)
    .tag(NAME_SOURCE_TAG, nameSource)
    .summary()
    ?.count() ?: 0L
