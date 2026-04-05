package com.nezumi_ai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.databinding.ActivityMainBinding
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.utils.WelcomeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private var dbInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 起動安定性優先: アプリ固有UIではActionBar/FABを使わない
            binding.toolbar.visibility = android.view.View.GONE
            binding.fab.hide()

            // 初回起動時にウェルカムダイアログを表示
            if (PreferencesHelper.isFirstLaunch(this)) {
                try {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    WelcomeDialog.show(this, navController)
                } catch (e: Exception) {
                    // ナビゲーションコントローラが取得できない場合はダイアログのみ表示
                    WelcomeDialog.show(this)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in onCreate", t)
            throw t
        }
    }

    override fun onStart() {
        super.onStart()
        
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
}
