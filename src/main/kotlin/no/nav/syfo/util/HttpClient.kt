package no.nav.syfo.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.serialization.jackson.jackson
import kotlin.time.Duration.Companion.seconds

fun httpClientDefault(httpClient: HttpClient = HttpClient(Apache5)): HttpClient = httpClient.config {
    expectSuccess = true
    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
        // Register custom converter for application/json-patch+json needed in Dialogporten
        register(JSON_PATCH_CONTENT_TYPE, JacksonConverter(jacksonObjectMapper()))
    }
    install(HttpRequestRetry) {
        retryOnExceptionIf(2) { _, cause ->
            cause !is ClientRequestException
        }
        constantDelay(500L)
    }
}

fun httpClientSSE(httpClient: HttpClient = HttpClient(Apache5) { engine { socketTimeout = 0 } }): HttpClient = httpClient.config {
    install(SSE) {
        maxReconnectionAttempts = 10
        reconnectionTime = 2.seconds
    }
}
