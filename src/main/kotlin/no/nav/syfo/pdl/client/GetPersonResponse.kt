package no.nav.syfo.pdl.client

data class GetPersonResponse(
    val data: ResponseData,
    val errors: List<ResponseError>?,
)

data class ResponseData(
    val person: PersonResponse?,
    val identer: IdentResponse?,
)

data class IdentResponse(
    val identer: List<Ident>,
)

data class Ident(
    val ident: String,
    val gruppe: String,
)

data class PersonResponse(
    val navn: List<Navn>?,
)

data class Navn(
    val fornavn: String,
    val mellomnavn: String?,
    val etternavn: String,
)

data class ResponseError(
    val message: String?,
    val locations: List<ErrorLocation>?,
    val path: List<String>?,
    val extensions: ErrorExtension?,
)

data class ErrorLocation(
    val line: String?,
    val column: String?,
)

data class ErrorExtension(
    val code: String?,
    val details: ErrorDetails?,
    val classification: String?,
)

data class ErrorDetails(
    val type: String? = null,
    val cause: String? = null,
    val policy: String? = null,
)
