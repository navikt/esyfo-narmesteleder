package no.nav.syfo.narmestelederbehov

import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.DialogportenSettings

data class NarmestelederbehovSettings(
    val creation: NarmestelederbehovCreationSettings,
    val expiry: NarmestelederbehovExpirySettings,
    val dialogporten: DialogportenSettings,
    val dialogDispatch: DialogDispatchLoopSettings,
)
