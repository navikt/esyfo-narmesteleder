package no.nav.syfo.ereg.client

data class Organisasjon(
    val organisasjonsnummer: String,
    val inngaarIJuridiskEnheter: List<Organisasjon>? = null,
    // Liste av virksomhet(er) som drives av organisasjonsledd
    val driverVirksomheter: List<Organisasjon>? = null,
    val bestaarAvOrganisasjonsledd: List<OrganisasjonsLeddWrapper>? = null,
) {

    /**
     * Henter ut alle organisasjonsnummer for organisasjon, inkludert organisasjonsnummer for juridiske enheter,
     * organisasjonsledd og deres juridiske enheter.
     */
    fun aggregerOrgnummereFraHierarki(): Set<String> {
        val orgnummerSet = mutableSetOf(organisasjonsnummer)
        inngaarIJuridiskEnheter
            ?.mapTo(orgnummerSet) { enhet -> enhet.organisasjonsnummer }
        bestaarAvOrganisasjonsledd
            ?.forEach { organisasjonsLeddWrapper ->
                orgnummerSet.addAll(organisasjonsLeddWrapper.organisasjonsledd.collectOrgnummer())
            }
        return orgnummerSet
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
) {
    fun collectOrgnummer(
        visitedOrgnummere: MutableSet<String> = mutableSetOf(),
    ): Set<String> {
        if (!visitedOrgnummere.add(organisasjonsnummer)) {
            return emptySet()
        }

        val orgnummerSet = mutableSetOf(organisasjonsnummer)
        inngaarIJuridiskEnheter
            ?.mapTo(orgnummerSet) { enhet -> enhet.organisasjonsnummer }
        organisasjonsleddOver
            ?.forEach { organisasjonsLeddWrapper ->
                orgnummerSet.addAll(organisasjonsLeddWrapper.organisasjonsledd.collectOrgnummer(visitedOrgnummere))
            }
        return orgnummerSet
    }
}
