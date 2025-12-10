package no.nav.syfo.ereg.client

data class Organisasjon(
    val organisasjonsnummer: String,
    val inngaarIJuridiskEnheter: List<Organisasjon>? = null,
    val driverVirksomheter: List<Organisasjon>? = null,
) {
    fun orgnummerSet(): Set<String> {
        val juridiskeEnheter = inngaarIJuridiskEnheter?.map { enhet -> enhet.organisasjonsnummer } ?: emptyList()
        return juridiskeEnheter.plus(organisasjonsnummer).toSet()
    }
}
