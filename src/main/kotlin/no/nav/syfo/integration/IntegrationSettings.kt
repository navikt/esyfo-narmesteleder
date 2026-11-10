package no.nav.syfo.integration

import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings

/** Settings for the external clients shared by several modules. */
data class IntegrationSettings(
    val aareg: AaregSettings,
    val dinesykmeldte: DinesykmeldteSettings,
    val ereg: EregSettings,
    val pdl: PdlSettings,
)
