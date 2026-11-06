package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ApplicationSettingsTest :
    FunSpec({
        test("application.conf keeps the previous defaults when only required variables are set") {
            settingsFrom(requiredEnvironment) shouldBe ApplicationSettings(
                narmestelederbehovSettings = NarmestelederbehovSettings(
                    persistLeesahNlBehov = true,
                    publicIngressUrl = "https://ingress.example",
                    frontendBaseUrl = "https://frontend.example",
                    dialogUpdatePollingInterval = 5.minutes,
                ),
                narmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                narmestelederrelasjonEmploymentSettings = NarmestelederrelasjonEmploymentSettings(
                    employmentCheckEnabled = false,
                    employmentCheckObserveSourceEnabled = false,
                    employmentCheckRevokeEnabled = false,
                ),
                narmestelederrelasjonKafkaSettings = NarmestelederrelasjonKafkaSettings(
                    persistRegister = false,
                    pdlConsumerEnabled = false,
                ),
                sykmeldingKafkaSettings = SykmeldingKafkaSettings(persistSendtSykmelding = false),
                dialogportenTaskSettings = DialogportenTaskSettings(enabled = true),
                maintenanceTaskSettings = MaintenanceTaskSettings(enabled = false, interval = 24.hours),
                personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(enabled = false, pollingInterval = 5.minutes),
                leaderElectionSettings = LeaderElectionSettings(
                    electorPath = "elector-path",
                    electorSseUrl = "http://elector.example/sse",
                ),
            )
        }

        test("environment variables override every default in application.conf") {
            val environment = requiredEnvironment + mapOf(
                "PERSIST_LEESAH_NL_BEHOV" to "false",
                "DIALOGPORTEN_UPDATE_POLLING_DELAY" to "10m",
                "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS" to "3",
                "EMPLOYMENT_CHECK_ENABLED" to "true",
                "EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED" to "true",
                "PERSIST_NARMESTELEDER_REGISTER" to "true",
                "PDL_LEESAH_CONSUMER_ENABLED" to "true",
                "PERSIST_SENDT_SYKMELDING" to "true",
                "BEHOV_MAINTENANCE_TASK_ENABLED" to "true",
                "MAINTENANCE_TASK_DELAY" to "1h",
                "PERSON_ENRICHMENT_TASK_ENABLED" to "true",
                "PERSON_ENRICHMENT_TASK_DELAY" to "30s",
            )

            assertSoftly(settingsFrom(environment)) {
                narmestelederbehovSettings.persistLeesahNlBehov shouldBe false
                narmestelederbehovSettings.dialogUpdatePollingInterval shouldBe 10.minutes
                narmestelederbehovExpirySettings.daysAfterTom shouldBe 3
                narmestelederrelasjonEmploymentSettings.employmentCheckEnabled shouldBe true
                narmestelederrelasjonEmploymentSettings.employmentCheckObserveSourceEnabled shouldBe true
                narmestelederrelasjonKafkaSettings.persistRegister shouldBe true
                narmestelederrelasjonKafkaSettings.pdlConsumerEnabled shouldBe true
                sykmeldingKafkaSettings.persistSendtSykmelding shouldBe true
                maintenanceTaskSettings shouldBe MaintenanceTaskSettings(enabled = true, interval = 1.hours)
                personEnrichmentTaskSettings shouldBe PersonEnrichmentTaskSettings(enabled = true, pollingInterval = 30.seconds)
            }
        }

        test("flags keep the lenient parsing where only a case-insensitive true enables them") {
            val settings = settingsFrom(
                requiredEnvironment + mapOf("EMPLOYMENT_CHECK_ENABLED" to "TRUE", "PERSIST_SENDT_SYKMELDING" to "yes"),
            )

            assertSoftly(settings) {
                narmestelederrelasjonEmploymentSettings.employmentCheckEnabled shouldBe true
                sykmeldingKafkaSettings.persistSendtSykmelding shouldBe false
            }
        }

        test("startup reports every missing required setting in one error") {
            val failure = shouldThrow<IllegalStateException> { settingsFrom(emptyMap()) }

            failure.message shouldBe "Invalid configuration: " +
                "narmestelederbehov.publicIngressUrl is missing, " +
                "narmestelederbehov.frontendBaseUrl is missing, " +
                "narmestelederbehov.dialogportenTask.enabled is missing, " +
                "leaderElection.path is missing, " +
                "leaderElection.sseUrl is missing"
        }

        test("startup reports invalid durations and numbers without their values") {
            val failure = shouldThrow<IllegalStateException> {
                settingsFrom(
                    requiredEnvironment + mapOf(
                        "DIALOGPORTEN_UPDATE_POLLING_DELAY" to "soon",
                        "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS" to "sixteen",
                    ),
                )
            }

            assertSoftly(failure.message.orEmpty()) {
                this shouldBe "Invalid configuration: " +
                    "narmestelederbehov.dialogUpdatePollingInterval is not a valid duration, " +
                    "narmestelederbehov.expiry.daysAfterTom is not a whole number"
                this shouldNotContain "soon"
                this shouldNotContain "sixteen"
            }
        }

        test("startup still rejects simultaneous employment revocation and source observation") {
            shouldThrow<IllegalArgumentException> {
                settingsFrom(
                    requiredEnvironment + mapOf(
                        "EMPLOYMENT_CHECK_REVOKE_ENABLED" to "true",
                        "EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED" to "true",
                    ),
                )
            }
        }
    })

private val requiredEnvironment = mapOf(
    "PUBLIC_INGRESS_URL" to "https://ingress.example",
    "FRONTEND_BASE_URL" to "https://frontend.example",
    "DIALOGPORTEN_TASK_ENABLED" to "true",
    "ELECTOR_PATH" to "elector-path",
    "ELECTOR_SSE_URL" to "http://elector.example/sse",
)

private fun settingsFrom(environment: Map<String, String>) = ApplicationSettings.from(applicationConfigWithEnvironment(environment))
