package no.nav.syfo.application.api

import com.fasterxml.jackson.databind.SerializationFeature
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.install
import io.ktor.server.application.Application
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import java.util.UUID

const val NAV_CALL_ID_HEADER = "Nav-Call-Id"

fun Application.installContentNegotiation() {
    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
        }
    }
}

fun Application.installCallId() {
    install(CallId) {
        retrieve { it.request.headers[NAV_CALL_ID_HEADER] }
        generate { UUID.randomUUID().toString() }
        verify { callId: String -> callId.isNotEmpty() }
        header(NAV_CALL_ID_HEADER)
    }
}

fun Application.installStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
}
