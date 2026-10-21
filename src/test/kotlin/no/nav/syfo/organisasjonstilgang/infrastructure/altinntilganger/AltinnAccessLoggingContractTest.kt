package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import io.kotest.core.spec.style.FunSpec
import no.nav.syfo.organisasjonstilgang.api.registerAccessibleOrganizationsApi
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase

class AltinnAccessLoggingContractTest :
    FunSpec({
        altinnFailureLoggingContract(
            endpoint = "organizations",
            path = "/access/organizations",
            capabilityLoggers = listOf(ListAccessibleOrganizationsUseCase::class.java.name),
        ) { upstream ->
            registerAccessibleOrganizationsApi(ListAccessibleOrganizationsUseCase(upstream.tilganger), upstream.authClient)
        }
    })
