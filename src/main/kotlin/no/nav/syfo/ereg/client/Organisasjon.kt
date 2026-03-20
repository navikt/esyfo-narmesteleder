package no.nav.syfo.ereg.client

data class Organisasjon(
    val organisasjonsnummer: String,
    val navn: Navn? = null,
    // Liste av hvilke(n) juridisk enhet organisasjonsledd inngår i
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

    /**
     * Finner den overordnede juridiske enheten i organisasjonshierarkiet ved å traversere
     * bestaarAvOrganisasjonsledd og organisasjonsleddOver oppover til toppen, og returnerer
     * den juridiske enheten (inngaarIJuridiskEnheter) som finnes på det høyeste nivået.
     */
    fun finnOverordnetJuridiskEnhet(): Organisasjon? {
        bestaarAvOrganisasjonsledd
            ?.firstNotNullOfOrNull { wrapper ->
                wrapper.organisasjonsledd.finnOverordnetJuridiskEnhetFraToppniva()
            }
            ?.let { return it }

        return inngaarIJuridiskEnheter?.firstOrNull()
    }

    fun getForetrukketNavn(): String? = navn?.sammensattnavn ?: navn?.navnelinje1
}
data class Navn(
    val sammensattnavn: String? = null,
    val navnelinje1: String? = null,
)
data class OrganisasjonsLeddWrapper(
    val organisasjonsledd: OrganisasjonsLedd,
)
data class OrganisasjonsLedd(
    val organisasjonsnummer: String,
    // Liste av virksomhet(er) som drives av organisasjonsledd
    val driverVirksomheter: List<Organisasjon>? = null,
    // Liste av hvilke(n) juridisk enhet organisasjonsledd inngår i
    val inngaarIJuridiskEnheter: List<Organisasjon>? = null,
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

    /**
     * Finner den overordnede juridiske enheten i organisasjonshierarkiet ved å traversere
     * bestaarAvOrganisasjonsledd og organisasjonsleddOver oppover til toppen, og returnerer
     * den juridiske enheten (inngaarIJuridiskEnheter) som finnes på det høyeste nivået.
     */
    fun finnOverordnetJuridiskEnhetFraToppniva(
        visitedOrgnummere: MutableSet<String> = mutableSetOf(),
    ): Organisasjon? {
        if (!visitedOrgnummere.add(organisasjonsnummer)) {
            return null
        }

        if (organisasjonsleddOver.isNullOrEmpty()) {
            return inngaarIJuridiskEnheter?.firstOrNull()
        }

        return organisasjonsleddOver.firstNotNullOfOrNull { wrapper ->
            wrapper.organisasjonsledd.finnOverordnetJuridiskEnhetFraToppniva(visitedOrgnummere)
        }
    }
}
