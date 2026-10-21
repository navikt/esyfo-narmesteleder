package no.nav.syfo.organisasjonstilgang.api

import io.kotest.core.spec.style.FunSpec
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.altinnFailureLoggingContract

class AccessibleOrganizationsAltinnFailureLoggingTest :
    FunSpec({
        altinnFailureLoggingContract(
            endpoint = "organizations",
            path = "/access/organizations",
            capabilityLoggers = listOf(ListAccessibleOrganizationsUseCase::class.java.name),
        ) { upstream ->
            registerAccessibleOrganizationsApi(ListAccessibleOrganizationsUseCase(upstream.tilganger), upstream.authClient)
        }
    })
