package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.narmestelederbehov.DialogDispatchLoopSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.DialogportenSettings
import no.nav.syfo.platform.config.duration
import no.nav.syfo.platform.config.flag
import no.nav.syfo.platform.config.long
import no.nav.syfo.platform.config.string

internal fun ApplicationConfig.narmestelederbehovSettings() = NarmestelederbehovSettings(
    expiry = NarmestelederbehovExpirySettings(daysAfterTom = long("narmestelederbehov.expiry.daysAfterTom")),
    dialogporten = DialogportenSettings(
        baseUrl = string("altinn.baseUrl"),
        publicIngressUrl = string("narmestelederbehov.dialogporten.publicIngressUrl"),
        frontendBaseUrl = string("narmestelederbehov.dialogporten.frontendBaseUrl"),
    ),
    dialogDispatch = DialogDispatchLoopSettings(
        enabled = flag("narmestelederbehov.dialogDispatch.enabled"),
        interval = duration("narmestelederbehov.dialogDispatch.interval"),
    ),
)
