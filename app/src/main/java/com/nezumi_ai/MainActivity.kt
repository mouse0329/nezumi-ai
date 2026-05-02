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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.InputType
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.databinding.ActivityMainBinding
import com.nezumi_ai.presentation.ui.adapter.DrawerHistoryAdapter
import com.nezumi_ai.presentation.ui.adapter.DrawerHistoryItem
import com.nezumi_ai.utils.PreferencesHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionRepository: ChatSessionRepository
    private lateinit var drawerHistoryAdapter: DrawerHistoryAdapter
    private var dbInitialized = false
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isAppInBackground = false
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

            // Setup drawer navigation
            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                val database = NezumiAiDatabase.getInstance(this)
                val settingsRepository = SettingsRepository(database.settingsDao(), database.chatSessionDao())
                val messageRepository = com.nezumi_ai.data.repository.MessageRepository(database.messageDao())
                sessionRepository = ChatSessionRepository(database.chatSessionDao(), settingsRepository, messageRepository)
                if (!isIncognitoModeActive) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            sessionRepository.deleteAllIncognitoSessions()
                            Log.d(TAG, "Cleaned up stale incognito sessions on startup")
                        }.onFailure {
                            Log.e(TAG, "Failed to cleanup stale incognito sessions on startup", it)
                        }
                    }
                }
                setupDrawer(navController)
                observeDrawerHistory()

                // ★ セットアップ未完了の場合は SetupWizardFragment に遷移
                if (!PreferencesHelper.isInitialSetupCompleted(this)) {
                    Log.d(TAG, "Initial setup not completed - navigating to setup wizard")
                    navController.navigate(R.id.setupWizardFragment)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to setup drawer navigation", e)
            }

            PreferencesHelper.isFirstLaunch(this)
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in onCreate", t)
            throw t
        }
    }

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
            onLongClick = { session ->
                showHistoryItemActions(session)
            },
            onListUpdated = {
                // リスト更新時に一番上にスクロール
                binding.drawerHistoryRecycler.smoothScrollToPosition(0)
            }
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
            if (navController.currentDestination?.id != R.id.toolsSettingsFragment) {
                navController.navigate(R.id.toolsSettingsFragment)
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
    }

    fun openChatSession(sessionId: Long) {
        if (isIncognitoModeActive) {
            lifecycleScope.launch {
                runCatching {
                    leaveIncognitoModeForNormalNavigation()
                }.onFailure {
                    Log.e(TAG, "Failed to leave incognito mode before opening normal session", it)
                }
                navigateToChatSession(sessionId)
            }
            return
        }
        navigateToChatSession(sessionId)
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
        
        // シークレットモードでない場合はFLAG_SECUREを削除
        if (!isIncognitoModeActive) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            Log.d(TAG, "Cleared FLAG_SECURE on app pause")
        }
        
        // アプリがバックグラウンドに入ったときにシークレットセッションを削除
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.deleteAllIncognitoSessions()
                Log.d(TAG, "Cleaned up all incognito sessions on app pause")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup incognito sessions on pause", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDrawerDateLabels()
        
        // バックグラウンドから復帰時に生体認証を実行
        if (isAppInBackground) {
            isAppInBackground = false
            // シークレットモード中の場合のみFLAG_SECUREを設定
            if (isIncognitoModeActive) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                Log.d(TAG, "Added FLAG_SECURE on resume (incognito mode)")
                showBiometricPrompt()
            }
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
                // ユーザーがキャンセルした場合（ERROR_NEGATIVE_BUTTON）
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    // ロック画面に留まる（オーバーレイは表示されたまま）
                    Log.d(TAG, "User cancelled authentication - staying on lock screen")
                } else {
                    // その他のエラーの場合は再試行オプションを表示
                    Log.d(TAG, "Authentication error occurred")
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Biometric authentication succeeded")
                // 認証成功時はオーバーレイを削除して FLAG_SECURE を解除
                removeAuthOverlay()
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
            .setNegativeButtonText("キャンセル")
            .setConfirmationRequired(true)
            .build()

        try {
            biometricPrompt?.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Biometric authentication not available", e)
            // 生体認証が利用できない場合もロック画面に留まる
        }
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

        // 鍵アイコン
        val fingerPrintView = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
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
            text = "生体認証でロック解除"
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

        // 「シークレットモードを終了」ボタン
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
        
        // FLAG_SECURE を解除
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
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
        
        // Database 初期化をここで遅延実行（Binder 負荷軽減）
        if (!dbInitialized) {
            dbInitialized = true
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val db = NezumiAiDatabase.getInstance(this@MainActivity)
                    SettingsRepository(db.settingsDao(), db.chatSessionDao())
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
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        removeAuthOverlay()
        PreferencesHelper.applyThemeMode(this)
        Log.d(TAG, "Cleared FLAG_SECURE and overlay on app destroy")
        
        // アプリ終了時にシークレットセッションを全て削除
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.deleteAllIncognitoSessions()
                Log.d(TAG, "Cleaned up all incognito sessions on app destruction")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup incognito sessions", e)
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

    private fun observeDrawerHistory() {
        lifecycleScope.launch {
            sessionRepository.getAllSessions().collectLatest { sessions ->
                latestDrawerSessions = sessions
                renderDrawerHistory(sessions)
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
        lastRenderedDrawerDayStartMillis = localDayStartMillis()
        val groupedSessions = groupSessionsByDate(sessions)
        drawerHistoryAdapter.submitList(groupedSessions)
        binding.drawerHistoryEmpty.visibility = if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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

        val grouped = mutableMapOf<String, MutableList<ChatSessionEntity>>()

        for (session in sessions) {
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

    private suspend fun leaveIncognitoModeForNormalNavigation() {
        if (!isIncognitoModeActive) return
        isIncognitoModeActive = false
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        PreferencesHelper.applyThemeMode(this)
        withContext(Dispatchers.IO) {
            sessionRepository.deleteAllIncognitoSessions()
        }
        Log.d(TAG, "Exited incognito mode for normal chat navigation")
    }

    private fun showHistoryItemActions(session: ChatSessionEntity) {
        val labels = arrayOf("リネーム", "削除")
        MaterialAlertDialogBuilder(this)
            .setTitle(session.name.ifBlank { "無題のチャット" })
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showRenameSessionDialog(session)
                    1 -> showDeleteSessionDialog(session)
                }
            }
            .show()
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
                            sessionRepository.deleteSession(session.id)
                        }
                    }.onFailure {
                        Log.e(TAG, "Failed to delete session", it)
                    }
                }
            }
            .show()
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
