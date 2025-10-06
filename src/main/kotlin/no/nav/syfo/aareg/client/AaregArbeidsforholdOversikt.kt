package no.nav.syfo.aareg.client

enum class IdentType {
    AKTORID,
    FOLKEREGISTERIDENT,
    ORGANISASJONSNUMMER,
}

enum class Rapporteringsordning {
    A_ORDNINGEN,
    FOER_A_ORDNINGEN
}

data class AaregArbeidsforholdOversikt(
    val arbeidsforholdoversikter: List<Arbeidsforholdoversikt> = emptyList()
)

data class Arbeidsforholdoversikt(
    val arbeidssted: Arbeidssted,
    val opplysningspliktig: Opplysningspliktig,
)

data class Opplysningspliktig(
    val type: OpplysningspliktigType,
    val identer: List<Ident>,
) {
    fun getJuridiskOrgnummer() =
        identer.firstOrNull {
            it.type == IdentType.ORGANISASJONSNUMMER
        }?.ident
}

data class Arbeidssted(
    val type: ArbeidsstedType,
    val identer: List<Ident>,
) {
    fun getOrgnummer() =
        identer.firstOrNull {
            it.type == IdentType.ORGANISASJONSNUMMER
        }?.ident
}

data class Ident(
    val type: IdentType,
    val ident: String,
    val gjeldende: Boolean,
)

enum class ArbeidsstedType {
    Underenhet,
    Person,
}

enum class OpplysningspliktigType {
    Hovedenhet,
    Person,
}
