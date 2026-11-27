package com.digifello.compressorfordrive.ui.Core

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

sealed interface DriveAuthState {
    data object Disconnected : DriveAuthState

    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuthState

    data class Connected(val email: String?, val accessToken: String) : DriveAuthState
}

class ConnectDrive(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("drive_connect", Context.MODE_PRIVATE)
    private val client = Identity.getAuthorizationClient(appContext)

    private val request: AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .build()

    val savedEmail: String? get() = prefs.getString(KEY_EMAIL, null)

    suspend fun signInWithGoogle(): DriveAuthState = authorize()

    suspend fun reloadUser(): DriveAuthState {
        if (savedEmail == null) return DriveAuthState.Disconnected
        return try {
            authorize()
        } catch (e: Exception) {
            DriveAuthState.Disconnected
        }
    }

    fun handleConsentResult(data: Intent?): DriveAuthState.Connected {
        val result = client.getAuthorizationResultFromIntent(data)
        return result.toConnected()
    }

    suspend fun accessToken(): String {
        return when (val state = authorize()) {
            is DriveAuthState.Connected -> state.accessToken
            is DriveAuthState.NeedsConsent -> error("Drive access was revoked. Reconnect from the app.")
            DriveAuthState.Disconnected -> error("Not connected")
        }
    }

    suspend fun disconnect() {
        val email = savedEmail
        prefs.edit().remove(KEY_EMAIL).apply()
        if (email != null) {
            try {
                val revoke = RevokeAccessRequest.builder()
                    .setAccount(Account(email, "com.google"))
                    .setScopes(listOf(Scope(DRIVE_SCOPE)))
                    .build()
                client.revokeAccess(revoke).await()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun authorize(): DriveAuthState {
        val result = client.authorize(request).await()
        return if (result.hasResolution()) {
            DriveAuthState.NeedsConsent(result.pendingIntent!!)
        } else {
            result.toConnected()
        }
    }

    private fun AuthorizationResult.toConnected(): DriveAuthState.Connected {
        val token = accessToken ?: error("No access token returned")
        val email = toGoogleSignInAccount()?.email ?: savedEmail
        if (email != null) prefs.edit().putString(KEY_EMAIL, email).apply()
        return DriveAuthState.Connected(email, token)
    }

    private companion object {
        const val KEY_EMAIL = "email"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}