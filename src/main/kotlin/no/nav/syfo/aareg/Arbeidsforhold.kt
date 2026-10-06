package no.nav.syfo.aareg

import no.nav.syfo.integration.aareg.ArbeidsstedType
import no.nav.syfo.integration.aareg.OpplysningspliktigType

data class Arbeidsforhold(
    val orgnummer: String,
    val arbeidsstedType: ArbeidsstedType,
    val opplysningspliktigOrgnummer: String?,
    val opplysningspliktigType: OpplysningspliktigType
) {
    fun toOrgnummerList(): List<String> = listOfNotNull(orgnummer, opplysningspliktigOrgnummer).distinct()
}

fun List<Arbeidsforhold>.getForOrgnummer(orgnummer: String): Arbeidsforhold? = this.firstOrNull { it.orgnummer == orgnummer }
