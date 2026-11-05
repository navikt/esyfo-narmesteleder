package no.nav.syfo.narmestelederbehov

data class NarmestelederbehovSettings(
    val persistLeesahNlBehov: Boolean,
    val publicIngressUrl: String,
    val frontendBaseUrl: String,
)
