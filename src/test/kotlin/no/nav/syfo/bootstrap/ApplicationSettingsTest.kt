package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ApplicationSettingsTest :
    FunSpec({
        test("application.conf keeps the previous defaults when only required variables are set") {
            val settings = settingsFrom(requiredEnvironment)

            settings shouldBe ApplicationSettings(
                aaregSettings = AaregSettings(baseUrl = "https://aareg.example", scope = "aareg-scope"),
                altinnApiSettings = AltinnApiSettings(baseUrl = "https://altinn.example"),
                altinnAuthorizationSettings = settings.altinnAuthorizationSettings,
                altinnAccessSettings = AltinnAccessSettings(baseUrl = "https://altinn-tilganger.example"),
                dinesykmeldteSettings = DinesykmeldteSettings(baseUrl = "https://dinesykmeldte.example", scope = "dinesykmeldte-scope"),
                dialogportenSettings = DialogportenSettings(baseUrl = "https://altinn.example"),
                eregSettings = EregSettings(baseUrl = "https://ereg.example"),
                pdlSettings = PdlSettings(baseUrl = "https://pdl.example", scope = "pdl-scope"),
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
            settings.altinnAuthorizationSettings.subscriptionKey shouldBe "subscription-key"
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
                "altinn.baseUrl is missing, " +
                "aareg.baseUrl is missing, " +
                "aareg.scope is missing, " +
                "altinn.authorization.subscriptionKey is missing, " +
                "altinn.tilganger.baseUrl is missing, " +
                "dinesykmeldte.baseUrl is missing, " +
                "dinesykmeldte.scope is missing, " +
                "ereg.baseUrl is missing, " +
                "pdl.baseUrl is missing, " +
                "pdl.scope is missing, " +
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
    "AAREG_BASE_URL" to "https://aareg.example",
    "AAREG_SCOPE" to "aareg-scope",
    "ALTINN_3_BASE_URL" to "https://altinn.example",
    "PDP_SUBSCRIPTION_KEY" to "subscription-key",
    "ALTINN_TILGANGER_BASE_URL" to "https://altinn-tilganger.example",
    "DINESYMELDTE_BASEURL" to "https://dinesykmeldte.example",
    "DINESYMELDTE_SCOPE" to "dinesykmeldte-scope",
    "EREG_BASE_URL" to "https://ereg.example",
    "PDL_BASE_URL" to "https://pdl.example",
    "PDL_SCOPE" to "pdl-scope",
)

private fun settingsFrom(environment: Map<String, String>) = ApplicationSettings.from(applicationConfigWithEnvironment(environment))
