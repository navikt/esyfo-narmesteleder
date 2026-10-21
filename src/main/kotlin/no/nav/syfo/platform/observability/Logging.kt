package no.nav.syfo.platform.observability

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun <T : Any> T.logger(): Logger = LoggerFactory.getLogger(this.javaClass)

fun logger(name: String): Logger = LoggerFactory.getLogger(name)
