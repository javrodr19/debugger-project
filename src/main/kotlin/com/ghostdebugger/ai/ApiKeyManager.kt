package com.ghostdebugger.ai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object ApiKeyManager {
    private const val SERVICE_NAME = "GhostDebugger"
    private const val KEY_NAME = "OPENAI_API_KEY"

    fun getApiKey(): String? {
        return try {
            val attributes = CredentialAttributes(generateServiceName(SERVICE_NAME, KEY_NAME))
            PasswordSafe.instance.getPassword(attributes)
        } catch (e: Exception) {
            // Fallback to system env for development
            System.getenv("OPENAI_API_KEY")
        }
    }

    fun setApiKey(key: String) {
        val attributes = CredentialAttributes(generateServiceName(SERVICE_NAME, KEY_NAME))
        val credentials = Credentials(KEY_NAME, key)
        PasswordSafe.instance.set(attributes, credentials)
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()
}
