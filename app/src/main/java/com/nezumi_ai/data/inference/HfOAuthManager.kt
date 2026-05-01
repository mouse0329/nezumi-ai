package com.nezumi_ai.data.inference

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object HfOAuthManager {

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://huggingface.co/oauth/authorize"),
        Uri.parse("https://huggingface.co/oauth/token")
    )

    fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            serviceConfig,
            ProjectConfig.HF_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(ProjectConfig.HF_REDIRECT_URI)
        )
            .setScope("openid profile read-repos")
            .build()
    }

    fun buildTokenRequest(response: AuthorizationResponse): TokenRequest {
        return response.createTokenExchangeRequest()
    }

    fun createAuthorizationRequestIntent(context: Context): Intent {
        val request = buildAuthorizationRequest()
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = request.toUri()
        return intent
    }

    private fun AuthorizationRequest.toUri(): Uri {
        val builder = Uri.Builder()
            .scheme("https")
            .authority("huggingface.co")
            .path("/oauth/authorize")
        configuration?.authorizationEndpoint?.let {
            builder.scheme(it.scheme).authority(it.authority).path(it.path)
        }
        builder.appendQueryParameter("client_id", clientId)
        builder.appendQueryParameter("response_type", responseType)
        builder.appendQueryParameter("redirect_uri", redirectUri.toString())
        scope?.let { builder.appendQueryParameter("scope", it) }
        state?.let { builder.appendQueryParameter("state", it) }
        return builder.build()
    }

    fun performTokenRequest(
        authService: AuthorizationService,
        tokenRequest: TokenRequest,
        callback: (accessToken: String?, errorMessage: String?) -> Unit
    ) {
        authService.performTokenRequest(tokenRequest) { tokenResponse, authException ->
            if (authException != null) {
                callback(null, authException.errorDescription ?: authException.message)
            } else {
                callback(tokenResponse?.accessToken, null)
            }
        }
    }

    suspend fun performTokenExchange(
        response: AuthorizationResponse,
        authService: AuthorizationService
    ): String? = suspendCancellableCoroutine { continuation ->
        val tokenRequest = buildTokenRequest(response)
        performTokenRequest(authService, tokenRequest) { accessToken, errorMessage ->
            if (errorMessage != null) {
                continuation.resumeWithException(Exception(errorMessage))
            } else {
                continuation.resume(accessToken)
            }
        }
    }
}
