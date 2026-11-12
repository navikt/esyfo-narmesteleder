package no.nav.syfo.bootstrap

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RecordingRevocationPublisher
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.ClaimedEmploymentCheck
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentHistoryLookup
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentHistoryResult
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentReconciliationRepository
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordingEmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordingEmploymentReconciliationRepository
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonDependencies
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class ConfigureEmploymentChecksTest :
    FunSpec({
        test("employment checks always seed and publish on startup") {
            val now = Instant.parse("2026-06-15T12:00:00Z")
            val claim = ClaimedEmploymentCheck(
                narmesteLederId = UUID.randomUUID(),
                organizationNumber = OrganizationNumber("000000000"),
                employeeIdent = PersonalIdentificationNumber("00000000000"),
                claimToken = UUID.randomUUID(),
                claimedAt = now,
            )
            val repository = RecordingEmploymentReconciliationRepository().apply { claims = listOf(claim) }
            val published = CompletableDeferred<PublishNarmestelederrelasjonRevocationCommand>()
            val overrides = module {
                single<EmploymentReconciliationRepository> { repository }
                single<EmploymentHistoryLookup> { EmploymentHistoryLookup { EmploymentHistoryResult.Found(emptyList()) } }
                single<EmploymentCheckMetrics> { RecordingEmploymentCheckMetrics() }
                single<PublishNarmestelederrelasjonRevocation> { RecordingRevocationPublisher { published.complete(it) } }
                single<Clock> { Clock.fixed(now, ZoneOffset.UTC) }
            }

            testApplication {
                application {
                    install(Koin) { modules(narmestelederrelasjonDependencies(), overrides) }
                    configureEmploymentChecks()
                    monitor.raise(ServerReady, environment)
                }
                startApplication()

                withTimeout(5.seconds) { published.await() }.initiator shouldBe RevocationInitiator.EMPLOYMENT_ENDED
                eventually(5.seconds) { repository.seedRequests.size shouldBe 1 }
            }
        }
    })
