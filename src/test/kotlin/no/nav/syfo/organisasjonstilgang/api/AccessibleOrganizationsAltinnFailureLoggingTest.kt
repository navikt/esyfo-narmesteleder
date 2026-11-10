package no.nav.syfo.organisasjonstilgang.api

import io.kotest.core.spec.style.FunSpec
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.altinnFailureLoggingContract
import no.nav.syfo.platform.auth.texas.authenticateMaskinportenOrTokenX

class AccessibleOrganizationsAltinnFailureLoggingTest :
    FunSpec({
        altinnFailureLoggingContract(
            endpoint = "organizations",
            path = "/access/organizations",
            capabilityLoggers = listOf(ListAccessibleOrganizationsUseCase::class.java.name),
        ) { upstream ->
            authenticateMaskinportenOrTokenX { registerAccessibleOrganizationsApi(ListAccessibleOrganizationsUseCase(upstream.tilganger)) }
        }
    })
