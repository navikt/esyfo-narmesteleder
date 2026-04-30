package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.getForOrgnummer
import no.nav.syfo.application.api.ErrorType

object ArbeidsforholdValidator {
    fun validateSmArbeidsforhold(
        sykmeldtArbeidsforhold: List<Arbeidsforhold>,
        orgNumberInRequest: String,
    ) {
        nlrequire(
            sykmeldtArbeidsforhold.isNotEmpty(),
            ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
        ) { "Employee on sick leave is missing employment in any organization" }
        val matchingArbeidsforhold = sykmeldtArbeidsforhold.getForOrgnummer(orgNumberInRequest)
        nlrequire(
            matchingArbeidsforhold != null,
            type = ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
        ) { "Employee on sick leave is missing employment in the organization indicated in the request" }
    }

    fun validateNarmesteLederAvkreft(
        sykmeldtArbeidsforhold: List<Arbeidsforhold>,
        orgNumberInRequest: String,
    ) {
        nlrequire(
            sykmeldtArbeidsforhold.isNotEmpty(),
            ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
        ) { "Employee on sick leave is missing employment in any organization" }
        nlrequire(
            sykmeldtArbeidsforhold.getForOrgnummer(orgNumberInRequest) != null,
            ErrorType.EMPLOYEE_MISSING_EMPLOYMENT_IN_ORG,
        ) { "Employee on sick leave does not have employment in the organization indicated in the request" }
    }
}
