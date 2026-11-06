package no.nav.syfo.platform.auth.texas

import no.nav.syfo.bootstrap.ApplicationSettings
import no.nav.syfo.platform.config.localApplicationConfig

fun localTexasSettings(): TexasSettings = ApplicationSettings.from(localApplicationConfig()).texas
