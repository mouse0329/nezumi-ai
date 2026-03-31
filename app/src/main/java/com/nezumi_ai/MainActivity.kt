package com.nezumi_ai

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.nezumi_ai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 起動安定性優先: アプリ固有UIではActionBar/FABを使わない
            binding.toolbar.visibility = android.view.View.GONE
            binding.fab.hide()
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error in onCreate", t)
            throw t
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
