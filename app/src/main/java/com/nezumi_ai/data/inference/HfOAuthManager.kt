package com.nezumi_ai.data.inference

import android.net.Uri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest

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
}
