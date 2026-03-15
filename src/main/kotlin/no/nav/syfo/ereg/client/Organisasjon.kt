package no.nav.syfo.ereg.client

data class Organisasjon(
    val organisasjonsnummer: String,
    val inngaarIJuridiskEnheter: List<Organisasjon>? = null,
    // Liste av virksomhet(er) som drives av organisasjonsledd
    val driverVirksomheter: List<Organisasjon>? = null,
    val bestaarAvOrganisasjonsledd: List<OrganisasjonsLeddWrapper>? = null,
) {
    fun orgnummerSet(): Set<String> {
        val juridiskeEnheter = inngaarIJuridiskEnheter?.map { enhet -> enhet.organisasjonsnummer } ?: emptyList()
        return juridiskeEnheter.plus(organisasjonsnummer).toSet()
    }
}
data class OrganisasjonsLeddWrapper(
    val organisasjonsledd: OrganisasjonsLedd,
)
data class OrganisasjonsLedd(
    val organisasjonsnummer: String,
    // Liste av virksomhet(er) som drives av organisasjonsledd
    val driverVirksomheter: List<Organisasjon>? = null,
    // Liste av hvilke(n) juridisk enhet organisasjonsledd inngår i
    val inngaarIJuridiskEnheter: List<Organisasjon>? = null,
    // Liste av hvilke organisasjonsledd som ligger under organisasjonsledd
    val organisasjonsleddUnder: List<OrganisasjonsLeddWrapper>? = null,
    // Liste av hvilke organisasjonsledd som ligger over organisasjonsledd
    val organisasjonsleddOver: List<OrganisasjonsLeddWrapper>? = null,
)
