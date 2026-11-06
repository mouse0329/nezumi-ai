package com.nezumi_ai.presentation.navigation

/**
 * Platform-independent application routes.
 *
 * Platform navigation hosts map these routes to their native destination
 * implementations. Keeping the route names here prevents common UI from
 * depending on Android Fragment or resource IDs.
 */
sealed interface AppRoute {
    object Arguments {
        const val SESSION_ID = "sessionId"
        const val IS_INCOGNITO = "isIncognito"
        const val SCROLL_TO_MESSAGE_ID = "scrollToMessageId"
        const val START_SECTION = "startSection"
        const val APP_ID = "appId"
    }

    data object Chat : AppRoute
    data class ChatSession(
        val sessionId: Long,
        val isIncognito: Boolean = false,
        val scrollToMessageId: Long? = null,
    ) : AppRoute

    data object Setup : AppRoute
    data class Settings(val startSection: Int? = null) : AppRoute
    data object SessionList : AppRoute
    data object License : AppRoute
    data object Help : AppRoute
    data object ModelSettings : AppRoute
    data object ModelManagement : AppRoute
    data object PresetSettings : AppRoute
    data object ImageGeneration : AppRoute
    data object Logs : AppRoute
    data object MiniAppManager : AppRoute
    data class MiniAppRunner(val appId: String) : AppRoute
}
