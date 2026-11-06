package no.nav.syfo.platform.auth.texas

import no.nav.syfo.bootstrap.ApplicationSettings
import no.nav.syfo.platform.config.localApplicationConfig

fun localTexasEnvironment(): TexasEnvironment = ApplicationSettings.from(localApplicationConfig()).texas
