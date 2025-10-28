package no.nav.syfo.dialogporten.domain

data class ApiAction(
    val action: String,
    val name: String,
    val endpoints: List<Endpoint>? = null,
) {
    data class Endpoint(
        val url: String,
        val httpMethod: HttpMethod,
        val documentationUrl: String,
    )

    enum class HttpMethod {
        GET,
        POST,
        PUT,
        DELETE,
        PATCH,
    }

    enum class Action(
        val value: String,
    ) {
        READ("read"),
        WRITE("write"),
    }
}
