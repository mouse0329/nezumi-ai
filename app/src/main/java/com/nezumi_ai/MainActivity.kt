package com.nezumi_ai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Gravity
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.ChatChunkRepository
import com.nezumi_ai.presentation.ui.screen.HistorySearchModal
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModelFactory
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.databinding.ActivityMainBinding
import com.nezumi_ai.presentation.ui.adapter.DrawerHistoryAdapter
import com.nezumi_ai.presentation.ui.adapter.DrawerHistoryItem
import com.nezumi_ai.utils.PreferencesHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import coil.load

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionRepository: ChatSessionRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var drawerHistoryAdapter: DrawerHistoryAdapter
    private var dbInitialized = false
    private var repositoriesReady = false
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isAppInBackground = false
    private var isFirstResume = true
    private var isIncognitoModeActive = false
    private var biometricPrompt: BiometricPrompt? = null
    private var authOverlayView: android.view.View? = null
    private var latestDrawerSessions: List<ChatSessionEntity> = emptyList()
    private var drawerDateRefreshJob: Job? = null
    private var lastRenderedDrawerDayStartMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isIncognitoModeActive = savedInstanceState?.getBoolean("is_incognito_mode_active") ?: false

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 起動安定性優先: アプリ固有UIではActionBar/FABを使わない
            binding.toolbar.visibility = android.view.View.GONE
            binding.fab.hide()

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
                    }
                    if (!isIncognitoModeActive) {
                        runCatching {
                            cr.deleteAllIncognitoSessions()
                            Log.d(TAG, "Cleaned up stale incognito sessions on startup")
                        }.onFailure {
                            Log.e(TAG, "Failed to cleanup stale incognito sessions on startup", it)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val navController = findNavController(R.id.nav_host_fragment_content_main)
                        setupDrawer(navController)
                        observeDrawerHistory()
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


    private fun showHistorySearchModal() {
        val database = NezumiAiDatabase.getInstance(applicationContext)
        val repository = ChatSessionRepository(database.chatSessionDao())
        val chunkRepository = ChatChunkRepository(database.chatChunkDao(), this)
        val factory = ChatSessionListViewModelFactory(repository, chunkRepository)
        val viewModel = androidx.lifecycle.ViewModelProvider(this, factory)[ChatSessionListViewModel::class.java]

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                androidx.compose.material3.MaterialTheme {
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
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    fun closeDrawer() {
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun setupDrawer(navController: androidx.navigation.NavController) {
        drawerHistoryAdapter = DrawerHistoryAdapter(
            onClick = { session ->
                closeDrawer()
                openChatSession(session.id)
            },
            onMenuClick = { session, anchorView ->
                showHistoryItemActions(session, anchorView)
            },
            onListUpdated = {
                // リスト更新時に一番上にスクロール
                binding.drawerHistoryRecycler.smoothScrollToPosition(0)
            },
            currentSessionId = getCurrentSessionId()
        )
        binding.drawerHistoryRecycler.layoutManager = LinearLayoutManager(this)
        binding.drawerHistoryRecycler.adapter = drawerHistoryAdapter
        binding.drawerSettingsButton.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.settingsFragment) {
                navController.navigate(R.id.settingsFragment)
            }
        }
        binding.drawerModelButton.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.modelSettingsFragment) {
                navController.navigate(R.id.modelSettingsFragment)
            }
        }
        binding.drawerToolsButton.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.presetSettingsFragment) {
                navController.navigate(R.id.presetSettingsFragment)
            }
        }
        binding.drawerNewChatButton.setOnClickListener {
            closeDrawer()
            createAndOpenSession()
        }
        binding.drawerIncognitoButton.setOnClickListener {
            closeDrawer()
            createAndOpenIncognitoSession()
        }
        binding.drawerImageGenButton.setOnClickListener {
            closeDrawer()
            if (navController.currentDestination?.id != R.id.imageGenFragment) {
                navController.navigate(R.id.imageGenFragment)
            }
        }
        binding.drawerSearchButton.setOnClickListener {
            closeDrawer()
            showHistorySearchModal()
        }
    }

    fun openChatSession(sessionId: Long) {
        if (isIncognitoModeActive) {
            lifecycleScope.launch {
                runCatching {
                    leaveIncognitoModeForNormalNavigation()
                }.onFailure {
                    Log.e(TAG, "Failed to leave incognito mode before opening normal session", it)
                }
                saveCurrentSessionId(sessionId)
                withContext(Dispatchers.IO) {
                    settingsRepository.saveCurrentSessionId(sessionId)
                }
                navigateToChatSession(sessionId)
            }
            return
        }
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

    private fun navigateToChatSession(sessionId: Long) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id == R.id.chatFragment) {
            navController.popBackStack(R.id.chatFragment, true)
        }
        navController.navigate(
            R.id.chatFragment,
            Bundle().apply { putLong("sessionId", sessionId) },
            navOptions {
                launchSingleTop = true
            }
        )
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
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
                    sessionRepository.deleteAllIncognitoSessions()
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

        // ★ スクリーンショット無効化設定をアプリ全体に反映。
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
            .setTitle("生体認証")
            .setSubtitle("アプリの再開には生体認証が必要です")
            .setNegativeButtonText(
                if (PreferencesHelper.hasSecretModePin(this)) "PINで解除" else "キャンセル"
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
            hint = "4桁のPIN"
            setText("")
            maxLines = 1
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("PINで解除")
            .setView(pinInput)
            .setPositiveButton("解除") { _, _ ->
                val pin = pinInput.text?.toString() ?: ""
                if (pin.length == 4 && PreferencesHelper.verifySecretModePin(ctx, pin)) {
                    removeAuthOverlay()
                    if (!PreferencesHelper.isDisableScreenshot(ctx)) {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    Log.d(TAG, "PIN authentication succeeded")
                } else {
                    Toast.makeText(ctx, "PINが正しくありません", Toast.LENGTH_SHORT).show()
                    showPasswordUnlockDialog()
                }
            }
            .setNegativeButton("キャンセル", null)
            .setOnDismissListener {
                // ロック画面を維持するため、閉じてもオーバーレイはそのままにする
            }
            .show()
    }

    private fun createAndShowAuthOverlay() {
        // すでに表示されている場合はスキップ
        if (authOverlayView != null) return

        // LinearLayout コンテナを作成
        val overlayContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
            isFocusable = true
            // クリックイベントを消費してアプリの操作をブロック
            setOnTouchListener { _, _ -> true }
            gravity = android.view.Gravity.CENTER
        }

        // 鍵アイコン（外部URLの Material Symbol に置換）
        val fingerPrintView = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                120.dp(),
                120.dp()
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            val colorFilter = android.graphics.PorterDuffColorFilter(
                android.graphics.Color.WHITE,
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            this.colorFilter = colorFilter
        }
        // Material Symbols のレンダーURLから読み込む
        fingerPrintView.load("https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/edit_off.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50")
        overlayContainer.addView(fingerPrintView)

        // 「ロック中」テキスト
        val lockStatusView = android.widget.TextView(this).apply {
            text = "認証待機中..."
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 30.dp()
            }
        }
        overlayContainer.addView(lockStatusView)

        // サブテキスト
        val subTextView = android.widget.TextView(this).apply {
            text = "生体認証またはPINでロック解除"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10.dp()
            }
        }
        overlayContainer.addView(subTextView)

        // ボタンコンテナ
        val buttonContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 60.dp()
                leftMargin = 50.dp()
                rightMargin = 50.dp()
            }
        }

        // 「もう一度試す」ボタン
        val retryButton = android.widget.Button(this).apply {
            text = "もう一度試す"
            textSize = 18f
            setBackgroundColor(android.graphics.Color.parseColor("#4A90E2"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                56.dp()
            ).apply {
                bottomMargin = 15.dp()
            }
            setOnClickListener {
                Log.d(TAG, "Retry button pressed")
                showBiometricPrompt()
            }
        }
        buttonContainer.addView(retryButton)

        // 「PINで解除」ボタン
        if (PreferencesHelper.hasSecretModePin(this)) {
            val pinUnlockButton = android.widget.Button(this).apply {
                text = "PINで解除"
                textSize = 18f
                setBackgroundColor(android.graphics.Color.parseColor("#6A5ACD"))
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    56.dp()
                ).apply {
                    bottomMargin = 15.dp()
                }
                setOnClickListener {
                    Log.d(TAG, "PIN unlock button pressed")
                    showPasswordUnlockDialog()
                }
            }
            buttonContainer.addView(pinUnlockButton)
        }

        // 「シークレットモードを終了」ボタン（シークレットモード時のみ表示）
        if (isIncognitoModeActive) {
            val exitButton = android.widget.Button(this).apply {
                text = "シークレットモードを終了"
                textSize = 18f
                setBackgroundColor(android.graphics.Color.parseColor("#E24A4A"))
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    56.dp()
                )
                setOnClickListener {
                    Log.d(TAG, "Exit incognito mode button pressed")
                    exitIncognitoMode()
                }
            }
            buttonContainer.addView(exitButton)
        }

        overlayContainer.addView(buttonContainer)

        authOverlayView = overlayContainer

        // 画面全体に追加
        (binding.root.parent as? android.view.ViewGroup)?.addView(
            authOverlayView,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        Log.d(TAG, "Auth overlay displayed")
    }

    private fun removeAuthOverlay() {
        authOverlayView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            Log.d(TAG, "Auth overlay removed")
        }
        authOverlayView = null
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
        PreferencesHelper.applyThemeMode(this)
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
        PreferencesHelper.applyThemeMode(this)
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
                    sessionRepository.deleteAllIncognitoSessions()
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
                if (::drawerHistoryAdapter.isInitialized) {
                    lastRenderedDrawerDayStartMillis = localDayStartMillis()
                    drawerHistoryAdapter.setCurrentSessionId(getCurrentSessionId())
                    drawerHistoryAdapter.submitList(grouped)
                    binding.drawerHistoryEmpty.visibility =
                        if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun refreshDrawerDateLabels() {
        val currentDayStart = localDayStartMillis()
        if (::drawerHistoryAdapter.isInitialized && currentDayStart != lastRenderedDrawerDayStartMillis) {
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

    private fun renderDrawerHistory(sessions: List<ChatSessionEntity>) {
        if (!::drawerHistoryAdapter.isInitialized) {
            Log.w(TAG, "renderDrawerHistory called before drawerHistoryAdapter initialization")
            return
        }
        lifecycleScope.launch {
            val grouped = withContext(Dispatchers.Default) { groupSessionsByDate(sessions) }
            lastRenderedDrawerDayStartMillis = localDayStartMillis()
            drawerHistoryAdapter.setCurrentSessionId(getCurrentSessionId())
            drawerHistoryAdapter.submitList(grouped)
            binding.drawerHistoryEmpty.visibility =
                if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<DrawerHistoryItem> {
        val result = mutableListOf<DrawerHistoryItem>()
        val calendar = Calendar.getInstance()
        val today = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayTime = today.timeInMillis

        // ピン留めセッションと通常セッションを分離
        val pinnedSessions = sessions.filter { it.isPinned }
        val unpinnedSessions = sessions.filter { !it.isPinned }

        // ピン留めセッションを最初に追加
        if (pinnedSessions.isNotEmpty()) {
            result.add(DrawerHistoryItem.Label("ピン留め"))
            pinnedSessions.forEach { session ->
                result.add(DrawerHistoryItem.Session(session))
            }
        }

        val grouped = mutableMapOf<String, MutableList<ChatSessionEntity>>()

        for (session in unpinnedSessions) {
            val sessionCal = Calendar.getInstance().apply { timeInMillis = session.lastUpdated }
            sessionCal.set(Calendar.HOUR_OF_DAY, 0)
            sessionCal.set(Calendar.MINUTE, 0)
            sessionCal.set(Calendar.SECOND, 0)
            sessionCal.set(Calendar.MILLISECOND, 0)
            val sessionTime = sessionCal.timeInMillis
            val daysDiff = ((todayTime - sessionTime) / (1000 * 60 * 60 * 24)).toInt()

            val label = when {
                daysDiff == 0 -> "今日"
                daysDiff == 1 -> "昨日"
                daysDiff == 2 -> "一昨日"
                daysDiff in 3..6 -> {
                    val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
                    val dayName = when (dayOfWeek) {
                        Calendar.MONDAY -> "月"
                        Calendar.TUESDAY -> "火"
                        Calendar.WEDNESDAY -> "水"
                        Calendar.THURSDAY -> "木"
                        Calendar.FRIDAY -> "金"
                        Calendar.SATURDAY -> "土"
                        Calendar.SUNDAY -> "日"
                        else -> ""
                    }
                    if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) "今週 ($dayName)" else "今週"
                }
                daysDiff in 7..13 -> {
                    val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
                    val dayName = when (dayOfWeek) {
                        Calendar.MONDAY -> "月"
                        Calendar.TUESDAY -> "火"
                        Calendar.WEDNESDAY -> "水"
                        Calendar.THURSDAY -> "木"
                        Calendar.FRIDAY -> "金"
                        Calendar.SATURDAY -> "土"
                        Calendar.SUNDAY -> "日"
                        else -> ""
                    }
                    if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) "先週 ($dayName)" else "先週"
                }
                else -> {
                    val monthsDiff = daysDiff / 30
                    if (monthsDiff == 1) "1ヶ月前"
                    else "${monthsDiff}ヶ月前"
                }
            }

            grouped.getOrPut(label) { mutableListOf() }.add(session)
        }

        // 順序を定義
        val labelOrder = listOf("今日", "昨日", "一昨日", "今週 (月)", "今週 (火)", "今週 (水)", "今週 (木)", "今週 (金)", "今週", "先週 (月)", "先週 (火)", "先週 (水)", "先週 (木)", "先週 (金)", "先週")
        val otherLabels = grouped.keys.filter { !labelOrder.contains(it) }.sorted().reversed()

        for (label in labelOrder + otherLabels) {
            grouped[label]?.let { sessionList ->
                result.add(DrawerHistoryItem.Label(label))
                sessionList.forEach { session ->
                    result.add(DrawerHistoryItem.Session(session))
                }
            }
        }

        return result
    }

    private fun createAndOpenSession() {
        lifecycleScope.launch {
            runCatching {
                leaveIncognitoModeForNormalNavigation()
                withContext(Dispatchers.IO) {
                    sessionRepository.createSession("新しいチャット")
                }
            }.onSuccess { sessionId ->
                openChatSession(sessionId)
            }.onFailure {
                Log.e(TAG, "Failed to create session", it)
            }
        }
    }

    private fun createAndOpenIncognitoSession() {
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
        PreferencesHelper.applyThemeMode(this)
        withContext(Dispatchers.IO) {
            sessionRepository.deleteAllIncognitoSessions()
        }
        Log.d(TAG, "Exited incognito mode for normal chat navigation")
    }

    private fun showHistoryItemActions(session: ChatSessionEntity, anchorView: View) {
        val pinTitle = if (session.isPinned) "固定を解除" else "固定"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_input_field)
            elevation = 12f
            setPadding(0, 4.dp(), 0, 4.dp())
        }

        lateinit var popupWindow: PopupWindow
        val dismiss: () -> Unit = { popupWindow.dismiss() }

        fun addActionItem(label: String, onClick: () -> Unit) {
            val itemView = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onClick()
                    dismiss()
                }
            }
            container.addView(
                itemView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addActionItem(pinTitle) { togglePinSession(session) }
        addActionItem("名前を変更") { showRenameSessionDialog(session) }
        addActionItem("削除") { showDeleteSessionDialog(session) }

        popupWindow = PopupWindow(
            container,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 12f
        }

        // アンカー位置を固定計算して表示。表示後に追従させない。
        anchorView.post {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val popupWidth = container.measuredWidth
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            val x = (location[0] + anchorView.width - popupWidth).coerceAtLeast(8.dp())
            val y = location[1] + anchorView.height + 4.dp()

            popupWindow.showAtLocation(binding.drawerLayout, Gravity.TOP or Gravity.START, x, y)
        }
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
                            sessionRepository.deleteSession(session.id)
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