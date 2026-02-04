package no.nav.syfo.aareg

import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType

data class Arbeidsforhold(
    val orgnummer: String,
    val arbeidsstedType: ArbeidsstedType,
    val opplysningspliktigOrgnummer: String?,
    val opplysningspliktigType: OpplysningspliktigType
)
