package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.DialogportenSettings
import no.nav.syfo.platform.config.ConfigReader

internal fun ConfigReader.narmestelederbehovSettings() = NarmestelederbehovSettings(
    creation = NarmestelederbehovCreationSettings(persistenceEnabled = flag("narmestelederbehov.creation.persistLeesahNlBehov")),
    expiry = NarmestelederbehovExpirySettings(daysAfterTom = long("narmestelederbehov.expiry.daysAfterTom")),
    dialogporten = DialogportenSettings(
        baseUrl = string("altinn.baseUrl"),
        tasksEnabled = flag("narmestelederbehov.dialogporten.tasksEnabled"),
        publicIngressUrl = string("narmestelederbehov.dialogporten.publicIngressUrl"),
        frontendBaseUrl = string("narmestelederbehov.dialogporten.frontendBaseUrl"),
        updatePollingInterval = duration("narmestelederbehov.dialogporten.updatePollingInterval"),
    ),
)
