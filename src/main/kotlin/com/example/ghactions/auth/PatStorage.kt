package com.example.ghactions.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger

/**
 * Stores GitHub PATs in IntelliJ's [PasswordSafe], keyed per host. Never logs the token.
 */
class PatStorage {
    private val log = Logger.getInstance(PatStorage::class.java)

    fun getToken(host: String): String? =
        PasswordSafe.instance.get(attributes(host))?.getPasswordAsString()

    fun setToken(host: String, token: String) {
        PasswordSafe.instance.set(attributes(host), Credentials("token", token))
        log.info("Stored PAT for host=$host (length=${token.length})")
    }

    fun clearToken(host: String) {
        PasswordSafe.instance.set(attributes(host), null)
        log.info("Cleared PAT for host=$host")
    }

    private fun attributes(host: String) =
        CredentialAttributes(generateServiceName(SERVICE_NAME, host))

    companion object {
        private const val SERVICE_NAME = "GhActionsPlugin"
    }
}
