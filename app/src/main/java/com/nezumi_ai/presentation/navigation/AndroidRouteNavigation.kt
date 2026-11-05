package com.nezumi_ai.presentation.navigation

import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.nezumi_ai.R

/**
 * Android adapter for the shared route model.
 *
 * The current Android host still uses Fragment destinations for compatibility;
 * callers do not need to know about nav_graph resource IDs.
 */
fun NavController.navigate(
    route: AppRoute,
    options: NavOptions? = null,
) {
    when (route) {
        AppRoute.Chat -> navigate(R.id.chatFragment, null, options)
        is AppRoute.ChatSession -> navigate(
            R.id.chatFragment,
            Bundle().apply {
                putLong("sessionId", route.sessionId)
                putBoolean("isIncognito", route.isIncognito)
                route.scrollToMessageId?.let { putLong("scrollToMessageId", it) }
            },
            options,
        )
        AppRoute.Setup -> navigate(R.id.setupWizardFragment, null, options)
        is AppRoute.Settings -> navigate(
            R.id.settingsFragment,
            Bundle().apply {
                route.startSection?.let { putInt("startSection", it) }
            }.takeIf { route.startSection != null },
            options,
        )
        AppRoute.SessionList -> navigate(R.id.sessionListFragment, null, options)
        AppRoute.License -> navigate(R.id.licenseFragment, null, options)
        AppRoute.Help -> navigate(R.id.helpFragment, null, options)
        AppRoute.ModelSettings -> navigate(R.id.modelSettingsFragment, null, options)
        AppRoute.ModelManagement -> navigate(R.id.modelManagementFragment, null, options)
        AppRoute.PresetSettings -> navigate(R.id.presetSettingsFragment, null, options)
        AppRoute.ImageGeneration -> navigate(R.id.imageGenFragment, null, options)
        AppRoute.Logs -> navigate(R.id.logsFragment, null, options)
        AppRoute.MiniAppManager -> navigate(R.id.miniAppManagerFragment, null, options)
        is AppRoute.MiniAppRunner -> navigate(
            R.id.miniAppRunnerFragment,
            Bundle().apply { putString("appId", route.appId) },
            options,
        )
    }
}
