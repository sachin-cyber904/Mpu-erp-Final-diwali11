package com.example.erp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey


enum class LoginType {
    ADMIN,
    STUDENT
}

object CredentialStore {

    private const val PREFS_NAME = "secure_login_prefs"

    private fun emailKey(type: LoginType) = "saved_email_${type.name}"
    private fun passwordKey(type: LoginType) = "saved_password_${type.name}"

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, type: LoginType, email: String, password: String) {
        try {
            getPrefs(context).edit()
                .putString(emailKey(type), email)
                .putString(passwordKey(type), password)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getEmail(context: Context, type: LoginType): String {
        return try {
            getPrefs(context).getString(emailKey(type), "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun getPassword(context: Context, type: LoginType): String {
        return try {
            getPrefs(context).getString(passwordKey(type), "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun hasSavedCredentials(context: Context, type: LoginType): Boolean {
        return getEmail(context, type).isNotEmpty() && getPassword(context, type).isNotEmpty()
    }

    fun clear(context: Context, type: LoginType) {
        try {
            getPrefs(context).edit()
                .remove(emailKey(type))
                .remove(passwordKey(type))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAll(context: Context) {
        try {
            getPrefs(context).edit().clear().apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}