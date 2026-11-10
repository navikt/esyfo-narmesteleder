package no.nav.syfo.bootstrap

import no.nav.syfo.platform.config.localApplicationConfig

/** Settings for local runs and tests, read from `application-local.conf`. */
fun localApplicationSettings(): ApplicationSettings = ApplicationSettings.from(localApplicationConfig())
