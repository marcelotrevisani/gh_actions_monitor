package com.example.ghactions.auth

import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifies that a credential can call `GET /user` against a host. Returns either the
 * authenticated user's login or a structured failure. Single, synchronous HTTP call —
 * intended only for the settings panel's "Test connection" button. Plan 2 replaces this
 * with the proper `GitHubClient` implementation.
 */
object TestConnection {

    private val log = Logger.getInstance(TestConnection::class.java)

    sealed class Result {
        data class Success(val login: String) : Result()
        data class Failure(val httpStatus: Int?, val message: String) : Result()
    }

    fun probe(baseUrl: String, token: String): Result {
        val url = URL(baseUrl.trimEnd('/') + "/user")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Authorization", "token $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "gh-actions-monitor/0.1")
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val login = LOGIN_RE.find(body)?.groupValues?.get(1)
                if (login != null) Result.Success(login)
                else Result.Failure(code, "Connected but response had no 'login' field")
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Result.Failure(code, "HTTP $code: ${err.take(200)}")
            }
        } catch (e: Exception) {
            log.warn("Test connection failed for $baseUrl", e)
            Result.Failure(null, e.message ?: e::class.java.simpleName)
        } finally {
            conn.disconnect()
        }
    }

    // Tiny inline regex avoids pulling in a JSON library in Plan 1.
    private val LOGIN_RE = Regex(""""login"\s*:\s*"([^"]+)"""")
}
