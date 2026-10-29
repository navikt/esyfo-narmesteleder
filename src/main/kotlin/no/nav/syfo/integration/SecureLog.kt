package no.nav.syfo.integration

import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Dedicated logger for team logs. Use this if you need secure logs (isolated from standard application logs).
val teamLogsLogger: Logger = LoggerFactory.getLogger("team-logs-logger")
