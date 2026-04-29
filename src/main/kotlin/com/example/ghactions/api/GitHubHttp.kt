package com.example.ghactions.api

import com.example.ghactions.auth.AuthSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the Ktor HTTP client used by [GitHubClient]. Pulled out so tests can inject a [MockEngine].
 */
object GitHubHttp {

    /** User agent string sent with every request. Bumped manually when the plugin version changes. */
    private const val USER_AGENT = "gh-actions-monitor/0.1"

    /**
     * Constructs a client. Pass [engine]=null in production (the CIO engine is used) or a
     * `MockEngine` from tests. [baseUrl] is e.g. `https://api.github.com` (no trailing slash).
     */
    fun create(
        baseUrl: String,
        auth: AuthSource,
        engine: HttpClientEngine? = null
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
            defaultRequest {
                // Resolve relative paths against the base URL.
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
        // IdeAccount tokens are looked up at request time by [GitHubClient]'s caller; for now
        // we treat IdeAccount as a placeholder until the IDE-account credential
        // lookup is wired (deferred to a later plan). PAT auth is fully functional.
        is AuthSource.IdeAccount -> "token <pending-ide-account-credentials>"
    }
}
