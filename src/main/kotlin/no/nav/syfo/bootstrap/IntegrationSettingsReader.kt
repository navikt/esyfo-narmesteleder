package no.nav.syfo.bootstrap

import no.nav.syfo.integration.IntegrationSettings
import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.platform.config.ConfigReader

internal fun ConfigReader.integrationSettings() = IntegrationSettings(
    aareg = AaregSettings(baseUrl = string("aareg.baseUrl"), scope = string("aareg.scope")),
    dinesykmeldte = DinesykmeldteSettings(baseUrl = string("dinesykmeldte.baseUrl"), scope = string("dinesykmeldte.scope")),
    ereg = EregSettings(baseUrl = string("ereg.baseUrl")),
    pdl = PdlSettings(baseUrl = string("pdl.baseUrl"), scope = string("pdl.scope")),
)
