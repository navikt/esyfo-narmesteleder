package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings

internal data class HttpClientsSettings(
    val aaregSettings: AaregSettings,
    val altinnApiSettings: AltinnApiSettings,
    val altinnAuthorizationSettings: AltinnAuthorizationSettings,
    val altinnAccessSettings: AltinnAccessSettings,
    val dinesykmeldteSettings: DinesykmeldteSettings,
    val dialogportenSettings: DialogportenSettings,
    val eregSettings: EregSettings,
    val pdlSettings: PdlSettings,
)
