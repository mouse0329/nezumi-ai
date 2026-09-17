package com.nezumi_ai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil.compose.AsyncImage
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.ChatChunkRepository
import com.nezumi_ai.presentation.ui.screen.DrawerContent
import com.nezumi_ai.presentation.ui.screen.DrawerHistoryEntry
import com.nezumi_ai.presentation.ui.screen.HistorySearchModal
import com.nezumi_ai.presentation.ui.screen.AuthLockScreen
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModelFactory
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme
import com.nezumi_ai.utils.CrashLogDialog
import com.nezumi_ai.utils.LocaleHelper
import com.nezumi_ai.data.model.sessionDateLabel
import com.nezumi_ai.R
import com.nezumi_ai.utils.PreferencesHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    // i18n: アプリの UI 言語 (SYSTEM / JA / EN) を履かせた Context を
    // Activity に作らせるため、attachBaseContext で LocaleHelper.wrap() を応用する。
    // これにより Activity 内の stringResource / getString は選択されたロケールの
    // strings.xml (日 or 英) を参照するようになる。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    companion object {
        private const val TAG = "MainActivity"
    }

    // Compose ModalNavigationDrawer の状態。onCreate の setContent 内で初期化する。
    // ドロワー開閉は ChatFragment 等から openDrawer()/closeDrawer() 経由で操作される。
    private var composeDrawerState: androidx.compose.material3.DrawerState? = null
    private var composeDrawerScope: kotlinx.coroutines.CoroutineScope? = null
    // ドロワー履歴リストの Compose State (旧 DrawerHistoryAdapter の submitList 相当)。
    private var drawerEntries by mutableStateOf<List<DrawerHistoryEntry>>(emptyList())
    private var drawerCurrentSessionId by mutableStateOf<Long?>(null)
    private var drawerSessionsEmpty by mutableStateOf(false)
    // DB と Flow の初回値を待つ間、空の履歴を「履歴なし」と誤認させない。
    private var drawerHistoryLoading by mutableStateOf(true)
    // 「・・・」メニュー対象のセッション (非 null で DropdownMenu を表示)
    private var drawerMenuSession by mutableStateOf<ChatSessionEntity?>(null)
    private lateinit var sessionRepository: ChatSessionRepository
    private lateinit var settingsRepository: SettingsRepository
    private var dbInitialized = false
    private var repositoriesReady = false
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isAppInBackground = false
    private var isFirstResume = true
    private var isIncognitoModeActive = false
    private var biometricPrompt: BiometricPrompt? = null
    private var authOverlayDialog: android.app.Dialog? = null
    private var latestDrawerSessions: List<ChatSessionEntity> = emptyList()
    private var drawerDateRefreshJob: Job? = null
    private var lastRenderedDrawerDayStartMillis: Long = 0L
    private var crashDialogPresentationAttempted = false
    // 現在のナビゲーション画面がサイドバー操作を許可するか (setContent 内で更新)。
    private var drawerEnabledByNav by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isIncognitoModeActive = savedInstanceState?.getBoolean("is_incognito_mode_active") ?: false

        try {
            // XML レイアウト (activity_main.xml) を廃止し、DrawerLayout + NavHost を
            // Compose の ModalNavigationDrawer + AndroidView(NavHostFragment) で構築する。
            setContent {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                composeDrawerState = drawerState
                composeDrawerScope = scope
                // サイドバーはチャット画面でのみ開けるようにする。
                // 設定・ミニアプリ・ミニアプリマネージャー・モデル管理などの
                // チャット以外の画面ではジェスチャー/ボタンの両方で無効化する。
                val drawerGesturesEnabled = drawerEnabledByNav
                if (!drawerGesturesEnabled && drawerState.isOpen) {
                    LaunchedEffect(drawerGesturesEnabled) { drawerState.close() }
                }

                NezumiComposeTheme {
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        gesturesEnabled = drawerGesturesEnabled,
                        drawerContent = {
                            ModalDrawerSheet(
                                modifier = Modifier.width(280.dp)
                            ) {
                                DrawerContent(
                                    entries = drawerEntries,
                                    currentSessionId = drawerCurrentSessionId,
                                    sessionsEmpty = drawerSessionsEmpty,
                                    historyLoading = drawerHistoryLoading,
                                    menuSessionId = drawerMenuSession?.id,
                                    onSessionClick = { session ->
                                        closeDrawer()
                                        openChatSession(session.id)
                                    },
                                    onSessionMenuClick = { session ->
                                        drawerMenuSession = session
                                    },
                                    onSessionMenuDismiss = { drawerMenuSession = null },
                                    onTogglePin = { session ->
                                        drawerMenuSession = null
                                        togglePinSession(session)
                                    },
                                    onRenameSession = { session ->
                                        drawerMenuSession = null
                                        showRenameSessionDialog(session)
                                    },
                                    onDeleteSession = { session ->
                                        drawerMenuSession = null
                                        showDeleteSessionDialog(session)
                                    },
                                    onSettingsClick = { navigateFromDrawer(R.id.settingsFragment) },
                                    onModelSettingsClick = { navigateFromDrawer(R.id.modelSettingsFragment) },
                                    onPresetSettingsClick = { navigateFromDrawer(R.id.presetSettingsFragment) },
                                    onMiniAppsClick = { navigateFromDrawer(R.id.miniAppManagerFragment) },
                                    onNewChatClick = {
                                        closeDrawer()
                                        createAndOpenSession()
                                    },
                                    onIncognitoClick = {
                                        closeDrawer()
                                        createAndOpenIncognitoSession()
                                    },
                                    onImageGenClick = { navigateFromDrawer(R.id.imageGenFragment) },
                                    onSearchClick = {
                                        closeDrawer()
                                        showHistorySearchModal()
                                    }
                                )
                            }
                        }
                    ) {
                        // content_main.xml の NavHostFragment 相当
                        AndroidView(
                            factory = { ctx ->
                                androidx.fragment.app.FragmentContainerView(ctx).apply {
                                    id = R.id.nav_host_fragment_content_main
                                    post {
                                        attachNavHostIfNeeded()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        // ドロワーが開いているときに戻るボタンでドロワーを閉じる
                        BackHandler(enabled = drawerState.isOpen) {
                            scope.launch { drawerState.close() }
                        }
                    }
                }
            }

            PreferencesHelper.isFirstLaunch(this)
            // DB初期化をIOスレッドで実行してメインスレッドのブロックを防ぐ
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val database = NezumiAiDatabase.getInstance(this@MainActivity)
                    val sr = SettingsRepository.fromDatabase(database)
                    val messageRepository = com.nezumi_ai.data.repository.MessageRepository(database.messageDao())
                    val cr = ChatSessionRepository(database.chatSessionDao(), sr, messageRepository)
                    withContext(Dispatchers.Main) {
                        settingsRepository = sr
                        sessionRepository = cr
                        repositoriesReady = true
                        // 起動時のシークレット履歴掃除は添付ファイル数に応じて時間がかかる。
                        // その完了を待つと通常の履歴 Flow の初回取得まで遅れるため、先に購読する。
                        observeDrawerHistory()
                    }
                    if (!isIncognitoModeActive) {
                        runCatching {
                            val ctx = applicationContext
                            cr.deleteAllIncognitoSessionsWithAttachments { imageUri, audioUri ->
                                com.nezumi_ai.data.media.MessageMediaStore.deleteMessageAttachments(
                                    ctx, imageUri, audioUri
                                )
                            }
                            Log.d(TAG, "Cleaned up stale incognito sessions on startup")
                        }.onFailure {
                            Log.e(TAG, "Failed to cleanup stale incognito sessions on startup", it)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val navController = findNavController(R.id.nav_host_fragment_content_main)
                        if (!PreferencesHelper.isInitialSetupCompleted(this@MainActivity)) {
                            Log.d(TAG, "Initial setup not completed - navigating to setup wizard")
                            navController.navigate(R.id.setupWizardFragment)
                        } else {
                            ensureCurrentSessionExists()
                        }
                    }
                }.onFailure { t ->
                    Log.e(TAG, "Fatal error in DB initialization", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in onCreate", t)
            throw t
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        showPendingCrashDialogAfterFirstFrame()
    }

    /**
     * クラッシュ通知は DB やナビゲーションの初期化と無関係に表示する。
     * 起動初期のクラッシュでそれらの初期化が失敗しても、次回起動時に通知を失わない。
     */
    private fun showPendingCrashDialogAfterFirstFrame() {
        if (crashDialogPresentationAttempted) return

        window.decorView.post {
            if (isFinishing || isDestroyed || crashDialogPresentationAttempted) return@post
            runCatching { CrashLogDialog.showIfPending(this@MainActivity) }
                .onSuccess { crashDialogPresentationAttempted = true }
                .onFailure { Log.w(TAG, "Failed to show crash log dialog", it) }
        }
    }

    private fun attachNavHostIfNeeded() {
        if (isFinishing || isDestroyed) return
        val fragmentManager = supportFragmentManager
        if (fragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) != null) return

        val navHost = androidx.navigation.fragment.NavHostFragment.create(R.navigation.nav_graph)
        fragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, navHost)
            .setPrimaryNavigationFragment(navHost)
            .commitNow()

        navHost.navController.addOnDestinationChangedListener { _, destination, _ ->
            drawerEnabledByNav = destination.id == R.id.chatFragment
        }
        drawerEnabledByNav = navHost.navController.currentDestination?.id == R.id.chatFragment
    }


    private fun showHistorySearchModal() {
        val database = NezumiAiDatabase.getInstance(applicationContext)
        val repository = ChatSessionRepository(database.chatSessionDao())
        val chunkRepository = ChatChunkRepository(database.chatChunkDao(), this)
        val factory = ChatSessionListViewModelFactory(repository, chunkRepository, applicationContext)
        val viewModel = androidx.lifecycle.ViewModelProvider(this, factory)[ChatSessionListViewModel::class.java]

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                com.nezumi_ai.presentation.ui.theme.NezumiComposeTheme {
                    HistorySearchModal(
                        viewModel = viewModel,
                        onResultClick = { sessionId, messageId ->
                            val bundle = android.os.Bundle().apply {
                                putLong("sessionId", sessionId)
                                putLong("scrollToMessageId", messageId)
                            }
                            findNavController(R.id.nav_host_fragment_content_main)
                                .navigate(R.id.chatFragment, bundle)
                            (window.decorView as? android.view.ViewGroup)?.removeView(this)
                        },
                        onDismiss = {
                            (window.decorView as? android.view.ViewGroup)?.removeView(this)
                        }
                    )
                }
            }
        }
        (window.decorView as android.view.ViewGroup).addView(
            composeView,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    fun isInIncognitoMode(): Boolean = isIncognitoModeActive

    fun openDrawer() {
        if (!drawerEnabledByNav) return
        val state = composeDrawerState ?: return
        composeDrawerScope?.launch { state.open() }
    }

    fun closeDrawer() {
        val state = composeDrawerState ?: return
        composeDrawerScope?.launch { state.close() }
    }

    /**
     * ドロワーのクイックナビからの画面遷移 (旧 setupDrawer の各ボタン)。
     * 現在のデスティネーションと同じなら二重 navigate を避ける。
     */
    private fun navigateFromDrawer(destinationId: Int) {
        closeDrawer()
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != destinationId) {
            navController.navigate(destinationId)
        }
    }

    fun openChatSession(sessionId: Long) {
        if (isIncognitoModeActive) {
            showIncognitoExitConfirmation {
                openNormalSessionLeavingIncognito {
                    proceedOpenChatSession(sessionId)
                }
            }
            return
        }
        proceedOpenChatSession(sessionId)
    }

    private fun showIncognitoExitConfirmation(onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.secret_mode_exit_confirm_title))
            .setMessage(getString(R.string.secret_mode_exit_confirm_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.secret_mode_exit_confirm_ok)) { _, _ ->
                onConfirmed()
            }
            .show()
    }

    private fun openNormalSessionLeavingIncognito(onExited: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching {
                leaveIncognitoModeForNormalNavigation()
            }.onFailure {
                Log.e(TAG, "Failed to leave incognito mode before opening normal session", it)
            }
            // 見た目はセッション駆動（ChatFragment が現在セッションの isIncognito を
            // 購読して色を決める）になったため、テーマ強制の巻き戻しや recreate() は不要。
            // recreate() は Activity 二重リローンチと lifecycleScope キャンセル連鎖
            // （JobCancellationException / Fragment detach クラッシュ）の原因だった。
            onExited()
        }
    }

    private fun proceedOpenChatSession(sessionId: Long) {
        saveCurrentSessionId(sessionId)
        lifecycleScope.launch(Dispatchers.IO) {
            settingsRepository.saveCurrentSessionId(sessionId)
        }
        navigateToChatSession(sessionId)
    }


    private fun saveCurrentSessionId(sessionId: Long) {
        val prefs = getSharedPreferences("nezumi_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("current_session_id", sessionId).apply()
    }

    private fun ensureCurrentSessionExists() {
        if (!repositoriesReady) return
        lifecycleScope.launch(Dispatchers.IO) {
            if (isIncognitoModeActive) return@launch

            val prefs = getSharedPreferences("nezumi_ai_prefs", Context.MODE_PRIVATE)
            val currentSessionId = prefs.getLong("current_session_id", -1L).takeIf { it != -1L }
            if (currentSessionId != null) {
                val currentSession = sessionRepository.getSessionById(currentSessionId)
                if (currentSession != null && !currentSession.isIncognito) {
                    return@launch
                }
            }

            val savedSessionId = runCatching { settingsRepository.loadCurrentSessionId() }.getOrNull()
            if (savedSessionId != null && savedSessionId > 0) {
                val savedSession = sessionRepository.getSessionById(savedSessionId)
                if (savedSession != null && !savedSession.isIncognito) {
                    saveCurrentSessionId(savedSessionId)
                    return@launch
                }
            }

            val latestSession = sessionRepository.getLatestSession()
            if (latestSession != null && !latestSession.isIncognito) {
                saveCurrentSessionId(latestSession.id)
                settingsRepository.saveCurrentSessionId(latestSession.id)
                return@launch
            }

            val newSessionId = sessionRepository.createSession("新しいチャット")
            settingsRepository.saveCurrentSessionId(newSessionId)
            saveCurrentSessionId(newSessionId)
        }
    }

    private fun navigateToChatSession(sessionId: Long, forceNewFragment: Boolean = false) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
 // セッション遷移最適化: すでに ChatFragment が表示されている場合は、
        //   フラグメントを再生成（popBackStack + navigate）する代わりに、
        //   既存の ChatFragment の switchSession() を呼んでセッションIDだけ切り替える。
        //   これによりフラグメントの再作成による重い処理（ViewBinding再生成、RecyclerView再構築、
        //   Compose再構成など）を回避し、ページ遷移の重さを軽減する。
        if (!forceNewFragment && navController.currentDestination?.id == R.id.chatFragment) {
            val chatFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
                ?.let { it as? androidx.navigation.fragment.NavHostFragment }
                ?.childFragmentManager
                ?.fragments
                ?.firstOrNull { it is com.nezumi_ai.presentation.ui.fragment.ChatFragment } as? com.nezumi_ai.presentation.ui.fragment.ChatFragment
            if (chatFragment != null && chatFragment.isAdded) {
                chatFragment.switchSession(sessionId)
                return
            }
        }
        // ChatFragment が表示されていない場合は通常通りナビゲートする
        if (navController.currentDestination?.id == R.id.chatFragment) {
            navController.popBackStack(R.id.chatFragment, true)
        }
        navController.navigate(
            R.id.chatFragment,
            Bundle().apply {
                putLong("sessionId", sessionId)
                putBoolean("isIncognito", false)
            },
            navOptions {
                launchSingleTop = true
            }
        )
    }

    override fun onBackPressed() {
        // ドロワーは Compose 側の BackHandler (setContent 内) が閉じる。
        // ここでは開状態だけ確認して消費済みかどうかを判定する。
        if (drawerEnabledByNav && composeDrawerState?.isOpen == true) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        // アプリがバックグラウンドに入ったことをマーク
        isAppInBackground = true
        Log.d(TAG, "App paused - marked as background")

        // シークレットモードでなく、かつスクリーンショット無効化設定もオフの場合のみFLAG_SECUREを削除
        if (!isIncognitoModeActive && !PreferencesHelper.isDisableScreenshot(this)) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            Log.d(TAG, "Cleared FLAG_SECURE on app pause")
        }

        // シークレットモード中の場合、バックグラウンド進入時に即時セッション削除は行わない
        if (!isIncognitoModeActive && repositoriesReady) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ctx = applicationContext
                    sessionRepository.deleteAllIncognitoSessionsWithAttachments { imageUri, audioUri ->
                        com.nezumi_ai.data.media.MessageMediaStore.deleteMessageAttachments(
                            ctx, imageUri, audioUri
                        )
                    }
                    Log.d(TAG, "Cleaned up all incognito sessions on app pause")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cleanup incognito sessions on pause", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDrawerDateLabels()

 // スクリーンショット無効化設定をアプリ全体に反映。
        //   このフラグは既存のシークレットモード/常時ロックの FLAG_SECURE ロジックとは独立して動作する。
        //   非有効のときも、シークレットモード/常時ロック側の既存制御を壊さないように、
        //   後続の clearFlags はここでは呼ばない。
        if (PreferencesHelper.isDisableScreenshot(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }

        // アプリ起動時（初回 onResume）またはバックグラウンドから復帰時に生体認証を実行
        val shouldLock = if (isFirstResume) {
            isFirstResume = false
            // 初回起動時: 常時ロックが有効な場合のみ認証を要求
            PreferencesHelper.isAlwaysLockEnabled(this)
        } else if (isAppInBackground) {
            isAppInBackground = false
            // バックグラウンドからの復帰: シークレットモード中または常時ロック有効時
            isIncognitoModeActive || PreferencesHelper.isAlwaysLockEnabled(this)
        } else {
            false
        }
        if (shouldLock) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            Log.d(TAG, "Added FLAG_SECURE on resume (incognito mode or always lock)")
            showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt() {
        // 認証中はオーバーレイビューで画面を完全に覆う
        createAndShowAuthOverlay()

        // BiometricPrompt のコールバック
        val authenticationCallback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.d(TAG, "Biometric error: $errString (code: $errorCode)")
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    if (PreferencesHelper.hasSecretModePin(this@MainActivity)) {
                        showPasswordUnlockDialog()
                    } else {
                        Log.d(TAG, "User cancelled authentication - staying on lock screen")
                    }
                } else {
                    Log.d(TAG, "Authentication error occurred")
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Biometric authentication succeeded")
                // 認証成功時はオーバーレイを削除して FLAG_SECURE を解除（スクリーンショット無効化設定が有効な場合は維持）
                removeAuthOverlay()
                if (!PreferencesHelper.isDisableScreenshot(this@MainActivity)) {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.d(TAG, "Biometric authentication failed")
                // 認証失敗時もロック画面に留まる
                Log.d(TAG, "Authentication failed - staying on lock screen")
            }
        }

        // BiometricPrompt の作成
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            authenticationCallback
        )

        // BiometricPromptInfo の作成
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.secret_mode_biometrics_title))
            .setSubtitle(getString(R.string.secret_mode_biometrics_subtitle))
            .setNegativeButtonText(
                if (PreferencesHelper.hasSecretModePin(this)) getString(R.string.secret_mode_pin_unlock) else getString(R.string.common_cancel)
            )
            .setConfirmationRequired(true)
            .build()

        try {
            biometricPrompt?.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Biometric authentication not available", e)
            if (PreferencesHelper.hasSecretModePin(this)) {
                showPasswordUnlockDialog()
            }
        }
    }

    private fun showPasswordUnlockDialog() {
        val ctx = this
        val pinInput = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.secret_mode_pin_hint)
            setText("")
            maxLines = 1
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.secret_mode_pin_unlock))
            .setView(pinInput)
            .setPositiveButton(getString(R.string.secret_mode_unlock)) { _, _ ->
                val pin = pinInput.text?.toString() ?: ""
                if (pin.length == 4 && PreferencesHelper.verifySecretModePin(ctx, pin)) {
                    removeAuthOverlay()
                    if (!PreferencesHelper.isDisableScreenshot(ctx)) {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    Log.d(TAG, "PIN authentication succeeded")
                } else {
                    Toast.makeText(ctx, getString(R.string.secret_mode_invalid_pin), Toast.LENGTH_SHORT).show()
                    showPasswordUnlockDialog()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setOnDismissListener {
                // ロック画面を維持するため、閉じてもオーバーレイはそのままにする
            }
            .show()
    }

    private fun createAndShowAuthOverlay() {
        // すでに表示されている場合はスキップ
        if (authOverlayDialog?.isShowing == true) return

        val hasPin = PreferencesHelper.hasSecretModePin(this)
        val inIncognito = isIncognitoModeActive

        // ロック画面を Compose で構築する (旧 LinearLayout 手組みの置き換え)。
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            // Dialog creates its own window, so it does not inherit the Activity's
            // ViewTree owners. Compose requires these before it attaches the view.
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeViewModelStoreOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                NezumiComposeTheme {
                    AuthLockScreen(
                        hasPin = hasPin,
                        inIncognito = inIncognito,
                        onRetry = {
                            Log.d(TAG, "Retry button pressed")
                            showBiometricPrompt()
                        },
                        onPinUnlock = {
                            Log.d(TAG, "PIN unlock button pressed")
                            showPasswordUnlockDialog()
                        },
                        onExitIncognito = {
                            Log.d(TAG, "Exit incognito mode button pressed")
                            exitIncognitoMode()
                        }
                    )
                }
            }
        }

        // Activity の通常 View では既存の Dialog/BottomSheet より下に回るため、
        // ロック画面自身を Dialog ウィンドウとして最前面に表示する。
        val lockDialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(
                composeView,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnDismissListener {
                if (authOverlayDialog === this) {
                    authOverlayDialog = null
                }
            }
        }
        authOverlayDialog = lockDialog
        lockDialog.show()
        lockDialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )

        Log.d(TAG, "Auth overlay displayed")
    }

    private fun removeAuthOverlay() {
        authOverlayDialog?.dismiss()
        authOverlayDialog = null
        Log.d(TAG, "Auth overlay removed")
    }

    private fun exitIncognitoMode() {
        // シークレットモード終了
        isIncognitoModeActive = false

        // オーバーレイを削除
        removeAuthOverlay()

        // FLAG_SECURE を解除（スクリーンショット無効化設定が有効な場合は維持）
        if (!PreferencesHelper.isDisableScreenshot(this)) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        Log.d(TAG, "Exited incognito mode - FLAG_SECURE cleared")

        // ホームに戻す
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    private fun handleAuthenticationFailed() {
        // FLAG_SECURE を解除してから終了
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        // アプリをホーム画面に戻す
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    override fun onStart() {
        super.onStart()

        // Register screen off receiver to stop generation when screen sleeps
        registerScreenOffReceiver()
        startDrawerDateRefreshTimer()

        if (!dbInitialized) {
            dbInitialized = true
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val db = NezumiAiDatabase.getInstance(this@MainActivity)
                    SettingsRepository.fromDatabase(db)
                        .initializeSettingsIfNeeded(applicationContext)
                }.onFailure {
                    Log.w(TAG, "LiteRT-LM (.litertlm) migration failed", it)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()

        // Unregister screen off receiver
        unregisterScreenOffReceiver()
        stopDrawerDateRefreshTimer()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isChangingConfigurations) {
            Log.d(TAG, "Skipping incognito cleanup during configuration change")
            return
        }

        // FLAG_SECURE と authOverlay を完全にクリア
        isIncognitoModeActive = false
        if (!PreferencesHelper.isDisableScreenshot(this)) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        removeAuthOverlay()
        PreferencesHelper.applyThemeMode(this)
        Log.d(TAG, "Cleared FLAG_SECURE and overlay on app destroy")

        // アプリ終了時にシークレットセッションを全て削除
        if (repositoriesReady) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val ctx = applicationContext
                    sessionRepository.deleteAllIncognitoSessionsWithAttachments { imageUri, audioUri ->
                        com.nezumi_ai.data.media.MessageMediaStore.deleteMessageAttachments(
                            ctx, imageUri, audioUri
                        )
                    }
                    Log.d(TAG, "Cleaned up all incognito sessions on app destruction")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cleanup incognito sessions", e)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("is_incognito_mode_active", isIncognitoModeActive)
        super.onSaveInstanceState(outState)
    }

    private fun registerScreenOffReceiver() {
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    Log.d(TAG, "Screen off detected - stopping generation")
                    stopGenerationOnScreenOff()
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        try {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen off receiver", e)
        }
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister screen off receiver", e)
            }
        }
        screenOffReceiver = null
    }

    private fun getCurrentSessionId(): Long? {
        val prefs = getSharedPreferences("nezumi_ai_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("current_session_id", -1L).takeIf { it != -1L }
    }

    private fun observeDrawerHistory() {
        lifecycleScope.launch {
            sessionRepository.getAllSessions().collectLatest { sessions ->
                latestDrawerSessions = sessions
                val grouped = withContext(Dispatchers.Default) { groupSessionsByDate(sessions) }
                lastRenderedDrawerDayStartMillis = localDayStartMillis()
                drawerCurrentSessionId = getCurrentSessionId()
                drawerEntries = grouped
                drawerSessionsEmpty = sessions.isEmpty()
                drawerHistoryLoading = false
            }
        }
    }

    private fun refreshDrawerDateLabels() {
        val currentDayStart = localDayStartMillis()
        if (currentDayStart != lastRenderedDrawerDayStartMillis) {
            renderDrawerHistory(latestDrawerSessions)
        }
    }

    private fun startDrawerDateRefreshTimer() {
        if (drawerDateRefreshJob?.isActive == true) return
        drawerDateRefreshJob = lifecycleScope.launch {
            while (true) {
                delay(millisUntilNextLocalDay() + 1_000L)
                refreshDrawerDateLabels()
            }
        }
    }

    private fun stopDrawerDateRefreshTimer() {
        drawerDateRefreshJob?.cancel()
        drawerDateRefreshJob = null
    }

    private fun millisUntilNextLocalDay(): Long {
        val now = Calendar.getInstance()
        val nextDay = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (nextDay.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
    }

    /**
     * ドロワーの履歴リストで「現在開いているセッション」のハイライトだけを更新する。
     * 履歴の内容 (並び・件数) は変わらず current_session_id だけが変わった場合に使う。
     * (セッション切り替え最適化で ChatFragment.switchSession が呼ばれるルートは
     *  getAllSessions() の Flow に変化が出ないため、ここで明示的に追随させる。)
     */
    fun refreshDrawerSessionHighlight() {
        // Compose State を更新するだけで DrawerContent が再構成されハイライトが追随する。
        drawerCurrentSessionId = getCurrentSessionId()
    }

    private fun renderDrawerHistory(sessions: List<ChatSessionEntity>) {
        lifecycleScope.launch {
            val grouped = withContext(Dispatchers.Default) { groupSessionsByDate(sessions) }
            lastRenderedDrawerDayStartMillis = localDayStartMillis()
            drawerCurrentSessionId = getCurrentSessionId()
            drawerEntries = grouped
            drawerSessionsEmpty = sessions.isEmpty()
        }
    }

    private fun localDayStartMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<DrawerHistoryEntry> {
        val result = mutableListOf<DrawerHistoryEntry>()
        val calendar = Calendar.getInstance()
        val today = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayTime = today.timeInMillis

        val pinnedSessions = sessions.filter { it.isPinned }
        val unpinnedSessions = sessions.filter { !it.isPinned }

        if (pinnedSessions.isNotEmpty()) {
            result.add(DrawerHistoryEntry.Label(getString(R.string.session_group_pinned)))
            pinnedSessions.forEach { session ->
                result.add(DrawerHistoryEntry.Session(session))
            }
        }

        // ラベル付けは GroupedChatSessions と共通 (今日 / 昨日 / 曜日 / 日付)。
        //   セッションは lastUpdated 降順なので、LinkedHashMap の挿入順が
        //   そのまま新しい順のグループ順になる。
        val grouped = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
        for (session in unpinnedSessions) {
            val label = sessionDateLabel(this, session.lastUpdated)
            grouped.getOrPut(label) { mutableListOf() }.add(session)
        }

        for (label in grouped.keys) {
            grouped[label]?.let { sessionList ->
                result.add(DrawerHistoryEntry.Label(label))
                sessionList.forEach { session ->
                    result.add(DrawerHistoryEntry.Session(session))
                }
            }
        }
        return result
    }


    fun createAndOpenSession() {
        if (isIncognitoModeActive) {
            showIncognitoExitConfirmation {
                openNormalSessionLeavingIncognito { createAndOpenSessionAfterIncognitoExit() }
            }
            return
        }
        lifecycleScope.launch { createAndOpenSessionAfterIncognitoExit() }
    }

    private suspend fun createAndOpenSessionAfterIncognitoExit() {
        runCatching {
            withContext(Dispatchers.IO) {
                sessionRepository.createSession("新しいチャット")
            }
        }.onSuccess { sessionId ->
            saveCurrentSessionId(sessionId)
            navigateToChatSession(sessionId, forceNewFragment = true)
        }.onFailure {
            Log.e(TAG, "Failed to create session", it)
        }
    }

    fun createAndOpenIncognitoSession() {
        lifecycleScope.launch {
            if (!ensureSecretModePinBeforeIncognito()) return@launch

            runCatching {
                withContext(Dispatchers.IO) {
                    sessionRepository.createSession("シークレット", isIncognito = true)
                }
            }.onSuccess { sessionId ->
                // シークレットモード開始
                isIncognitoModeActive = true
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                Log.d(TAG, "Entered incognito mode - FLAG_SECURE set")

                val navController = findNavController(R.id.nav_host_fragment_content_main)
                if (navController.currentDestination?.id == R.id.chatFragment) {
                    navController.popBackStack(R.id.chatFragment, true)
                }
                navController.navigate(
                    R.id.chatFragment,
                    Bundle().apply {
                        putLong("sessionId", sessionId)
                        putBoolean("isIncognito", true)
                    },
                    navOptions { launchSingleTop = true }
                )
            }.onFailure {
                Log.e(TAG, "Failed to create incognito session", it)
            }
        }
    }

    private fun ensureSecretModePinBeforeIncognito(): Boolean {
        if (PreferencesHelper.hasSecretModePin(this)) return true

        MaterialAlertDialogBuilder(this)
            .setTitle("PIN を設定してください")
            .setMessage("シークレットモードを使用するには 4 桁の PIN を設定する必要があります。設定画面で PIN を登録してください。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("設定画面へ") { _, _ ->
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                if (navController.currentDestination?.id != R.id.settingsFragment) {
                    navController.navigate(R.id.settingsFragment)
                }
            }
            .show()

        return false
    }

    private suspend fun leaveIncognitoModeForNormalNavigation() {
        if (!isIncognitoModeActive) return
        isIncognitoModeActive = false
        if (!PreferencesHelper.isDisableScreenshot(this)) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        withContext(Dispatchers.IO) {
            val ctx = applicationContext
            sessionRepository.deleteAllIncognitoSessionsWithAttachments { imageUri, audioUri ->
                com.nezumi_ai.data.media.MessageMediaStore.deleteMessageAttachments(
                    ctx, imageUri, audioUri
                )
            }
        }
        Log.d(TAG, "Exited incognito mode for normal chat navigation")
    }


    private fun togglePinSession(session: ChatSessionEntity) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    sessionRepository.togglePinSession(session.id)
                }
            }.onFailure {
                Log.e(TAG, "Failed to toggle pin session", it)
            }
        }
    }

    private fun showRenameSessionDialog(session: ChatSessionEntity) {
        val input = TextInputEditText(this).apply {
            setText(session.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text?.length ?: 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("チャット名を変更")
            .setView(input)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            sessionRepository.updateSessionName(session.id, newName)
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to rename session", it)
                    }
                }
            }
            .show()
    }

    private fun showDeleteSessionDialog(session: ChatSessionEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("チャットを削除")
            .setMessage("「${session.name.ifBlank { "無題のチャット" }}」を削除します。よろしいですか？")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("削除") { _, _ ->
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (session.id == getCurrentSessionId()) {
                                clearCurrentSessionId()
                            }
                            // セッション削除と同時に添付ファイル (画像 / 音声 / 動画) も掃除する。
                            val ctx = applicationContext
                            sessionRepository.deleteSessionWithAttachments(session.id) { imageUri, audioUri ->
                                com.nezumi_ai.data.media.MessageMediaStore.deleteMessageAttachments(
                                    ctx, imageUri, audioUri
                                )
                            }
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to delete session", it)
                    }
                }
            }
            .show()
    }

    private fun clearCurrentSessionId() {
        val prefs = getSharedPreferences("nezumi_ai_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("current_session_id").apply()
        lifecycleScope.launch(Dispatchers.IO) {
            settingsRepository.saveCurrentSessionId(-1L)
        }
    }

    private fun stopGenerationOnScreenOff() {
        try {
            val currentFragment = supportFragmentManager.primaryNavigationFragment
            if (currentFragment is com.nezumi_ai.presentation.ui.fragment.ChatFragment) {
                currentFragment.stopGeneration()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop generation on screen off", e)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // 起動安定性優先: 既定メニューは表示しない
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        return runCatching {
            val navController = findNavController(R.id.nav_host_fragment_content_main)
            navController.navigateUp() || super.onSupportNavigateUp()
        }.getOrElse {
            Log.e(TAG, "navigateUp failed", it)
            super.onSupportNavigateUp()
        }
    }

    // dp単位をpixelに変換するヘルパー関数
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
