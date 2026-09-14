package no.nav.syfo.logging

import no.nav.esyfo.observability.ApplicationLogger
import no.nav.esyfo.observability.createLogger
import org.slf4j.LoggerFactory

fun applicationLogger(owner: Class<*>): ApplicationLogger = createLogger(LoggerFactory.getLogger(owner))
