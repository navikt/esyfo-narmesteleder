package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.DialogportenSettings
import no.nav.syfo.platform.config.ConfigReader

internal fun ConfigReader.narmestelederbehovSettings() = NarmestelederbehovSettings(
    creation = NarmestelederbehovCreationSettings(persistenceEnabled = flag("narmestelederbehov.persistLeesahNlBehov")),
    expiry = NarmestelederbehovExpirySettings(daysAfterTom = long("narmestelederbehov.expiry.daysAfterTom")),
    dialogporten = DialogportenSettings(
        baseUrl = string("altinn.baseUrl"),
        tasksEnabled = flag("narmestelederbehov.dialogportenTask.enabled"),
        publicIngressUrl = string("narmestelederbehov.publicIngressUrl"),
        frontendBaseUrl = string("narmestelederbehov.frontendBaseUrl"),
        updatePollingInterval = duration("narmestelederbehov.dialogUpdatePollingInterval"),
    ),
)
