package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten

data class DialogportenSettings(
    val baseUrl: String,
    val tasksEnabled: Boolean,
    val publicIngressUrl: String,
    val frontendBaseUrl: String,
)
