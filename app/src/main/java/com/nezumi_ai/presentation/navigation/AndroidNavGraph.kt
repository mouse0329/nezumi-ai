package com.nezumi_ai.presentation.navigation

import androidx.navigation.NavGraph
import androidx.navigation.NavType
import androidx.navigation.fragment.fragment
import androidx.navigation.createGraph
import androidx.navigation.NavController
import com.nezumi_ai.R
import com.nezumi_ai.presentation.ui.fragment.ChatFragment
import com.nezumi_ai.presentation.ui.fragment.HelpFragment
import com.nezumi_ai.presentation.ui.fragment.ImageGenFragment
import com.nezumi_ai.presentation.ui.fragment.LicenseFragment
import com.nezumi_ai.presentation.ui.fragment.LogsFragment
import com.nezumi_ai.presentation.ui.fragment.MiniAppManagerFragment
import com.nezumi_ai.presentation.ui.fragment.MiniAppRunnerFragment
import com.nezumi_ai.presentation.ui.fragment.ModelManagementFragment
import com.nezumi_ai.presentation.ui.fragment.ModelSettingsFragment
import com.nezumi_ai.presentation.ui.fragment.PresetSettingsFragment
import com.nezumi_ai.presentation.ui.fragment.SessionListFragment
import com.nezumi_ai.presentation.ui.fragment.SettingsComposeFragment
import com.nezumi_ai.presentation.ui.fragment.SetupWizardFragment

fun NavController.createAppGraph(): NavGraph = createGraph(
    startDestination = R.id.chatFragment,
) {
    fragment<SetupWizardFragment>(R.id.setupWizardFragment)
    fragment<SettingsComposeFragment>(R.id.settingsFragment)
    fragment<SessionListFragment>(R.id.sessionListFragment)
    fragment<LicenseFragment>(R.id.licenseFragment)
    fragment<HelpFragment>(R.id.helpFragment)
    fragment<ChatFragment>(R.id.chatFragment) {
        argument("sessionId") {
            type = NavType.LongType
            defaultValue = -1L
        }
        argument("isIncognito") {
            type = NavType.BoolType
            defaultValue = false
        }
        argument("scrollToMessageId") {
            type = NavType.LongType
            defaultValue = -1L
        }
    }
    fragment<ModelSettingsFragment>(R.id.modelSettingsFragment)
    fragment<ModelManagementFragment>(R.id.modelManagementFragment)
    fragment<PresetSettingsFragment>(R.id.presetSettingsFragment)
    fragment<ImageGenFragment>(R.id.imageGenFragment)
    fragment<LogsFragment>(R.id.logsFragment)
    fragment<MiniAppManagerFragment>(R.id.miniAppManagerFragment)
    fragment<MiniAppRunnerFragment>(R.id.miniAppRunnerFragment) {
        argument("appId") {
            type = NavType.StringType
            defaultValue = ""
        }
    }
}
