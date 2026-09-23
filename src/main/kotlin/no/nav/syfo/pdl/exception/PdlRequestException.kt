package no.nav.syfo.pdl.exception

open class PdlRequestException(
    message: String,
    cause: Throwable? = null
) : PdlException(message, cause)

class PdlIncompleteResponseException(
    val requestedCount: Int,
    val missingCount: Int,
) : PdlRequestException("PDL bulk response did not contain all requested results") {
    companion object {
        const val ERROR_CODE = "PDL_BULK_RESPONSE_INCOMPLETE"
    }
}
