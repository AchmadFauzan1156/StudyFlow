package id.studyflow.data.remote

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** Konfigurasi bersama klien HTTP: JSON dan batas waktu. Engine dipilih per platform. */
fun HttpClientConfig<*>.studyFlowDefaults() {
    install(ContentNegotiation) { json(ApiJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
        socketTimeoutMillis = 30_000
    }
    expectSuccess = false
}
