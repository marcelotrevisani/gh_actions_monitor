package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the Ktor HTTP client used by [GitHubClient]. Pulled out so tests can inject a [MockEngine].
 */
object GitHubHttp {

    private const val USER_AGENT = "gh-actions-monitor/0.1"

    /**
     * Constructs a client. Pass [engine]=null in production (the CIO engine is used) or a
     * `MockEngine` from tests. [baseUrl] is e.g. `https://api.github.com` (no trailing slash).
     *
     * [onResponse] is invoked on the IO dispatcher for every response (success or failure)
     * and is the integration point for `RateLimitTracker`. Tests typically pass `null`.
     */
    fun create(
        baseUrl: String,
        auth: AuthSource,
        engine: HttpClientEngine? = null,
        onResponse: ((RateLimitInfo) -> Unit)? = null
    ): HttpClient {
        val cleanBase = baseUrl.trimEnd('/')

        val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
            if (onResponse != null) {
                install(ResponseObserver) {
                    onResponse { response ->
                        onResponse(RateLimitInfo.fromHeaders(response.headers))
                    }
                }
            }
            defaultRequest {
                url.takeFrom(cleanBase)
                headers.append(HttpHeaders.Accept, "application/vnd.github+json")
                headers.append("X-GitHub-Api-Version", "2022-11-28")
                headers.append(HttpHeaders.UserAgent, USER_AGENT)
                headers.append(HttpHeaders.Authorization, authHeader(auth))
            }
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(CIO, config)
    }

    private fun authHeader(auth: AuthSource): String = when (auth) {
        is AuthSource.Pat -> "token ${auth.token}"
        is AuthSource.IdeAccount -> "token <pending-ide-account-credentials>"
    }
}
