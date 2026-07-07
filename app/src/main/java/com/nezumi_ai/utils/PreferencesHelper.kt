package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import java.security.MessageDigest

object PreferencesHelper {
    private const val PREF_NAME = "app_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch"
    private const val KEY_INITIAL_SETUP_COMPLETED = "initial_setup_completed"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_SD_MODEL_PATH = "sd_model_path"
    private const val KEY_SD_BACKEND = "sd_backend"
    private const val KEY_SD_STEPS = "sd_steps"
    private const val KEY_SD_CFG = "sd_cfg"
    private const val KEY_CURRENT_PRESET_ID = "current_preset_id"
    private const val KEY_BRAVE_SEARCH_API_KEY = "brave_search_api_key"
    private const val KEY_ENABLE_THINKING = "enable_thinking"
    private const val KEY_REQUIRE_MULTIMODAL = "require_multimodal"
    private const val KEY_SECRET_MODE_PIN_HASH = "secret_mode_pin_hash"
    private const val KEY_SECRET_MODE_ENABLED = "secret_mode_enabled"
    private const val KEY_ALWAYS_LOCK_ENABLED = "always_lock_enabled"
    private const val KEY_STOP_KEYBOARD_LEARNING = "stop_keyboard_learning"
    private const val KEY_SD_USE_OPENCL = "sd_use_opencl"

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_LIGHT = "LIGHT"
    const val THEME_DARK = "DARK"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(pin.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isFirstLaunch(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        val isFirst = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        if (isFirst) {
            // フラグを更新して次回からは false を返すようにする
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
        }
        return isFirst
    }

    fun resetFirstLaunchFlag(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }

    fun getThemeMode(context: Context): String {
        val prefs = getSharedPreferences(context)
        return prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        val normalized = when (mode.uppercase()) {
            THEME_LIGHT -> THEME_LIGHT
            THEME_DARK -> THEME_DARK
            else -> THEME_SYSTEM
        }
        val prefs = getSharedPreferences(context)
        prefs.edit().putString(KEY_THEME_MODE, normalized).apply()
    }

    fun applyThemeMode(context: Context) {
        val mode = getThemeMode(context)
        val nightMode = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun isInitialSetupCompleted(context: Context): Boolean {
        val prefs = getSharedPreferences(context)
        return prefs.getBoolean(KEY_INITIAL_SETUP_COMPLETED, false)
    }

    fun markInitialSetupCompleted(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETED, true).apply()
    }

    fun resetInitialSetupCompleted(context: Context) {
        val prefs = getSharedPreferences(context)
        prefs.edit().putBoolean(KEY_INITIAL_SETUP_COMPLETED, false).apply()
    }

    fun getSdModelPath(context: Context): String {
        return getSharedPreferences(context).getString(KEY_SD_MODEL_PATH, "") ?: ""
    }

    fun setSdModelPath(context: Context, path: String) {
        getSharedPreferences(context).edit().putString(KEY_SD_MODEL_PATH, path.trim()).apply()
    }

    fun getSdBackend(context: Context): String {
        // NOTE: "auto" は廃止。UI は CPU / GPU/NPU の 2 選択のみとし、
        // ここで下位互換のため auto を qnn にマップして返す。
        val raw = getSharedPreferences(context).getString(KEY_SD_BACKEND, "qnn") ?: "qnn"
        return when (raw.lowercase()) {
            "mnn", "cpu" -> "mnn"
            "qnn", "npu", "gpu", "gpu_npu", "auto" -> "qnn"
            else -> "qnn"
        }
    }

    fun setSdBackend(context: Context, backend: String) {
        // 入力のバリエーションを含めて mnn / qnn に正規化して保存する。
        val normalized = when (backend.lowercase()) {
            "mnn", "cpu" -> "mnn"
            else -> "qnn"
        }
        getSharedPreferences(context).edit().putString(KEY_SD_BACKEND, normalized).apply()
    }

    fun getSdSteps(context: Context): Int {
        return getSharedPreferences(context).getInt(KEY_SD_STEPS, 8)
    }

    fun setSdSteps(context: Context, steps: Int) {
        getSharedPreferences(context).edit().putInt(KEY_SD_STEPS, steps.coerceIn(1, 50)).apply()
    }

    fun getSdCfg(context: Context): Float {
        return getSharedPreferences(context).getFloat(KEY_SD_CFG, 7.0f)
    }

    fun setSdCfg(context: Context, cfg: Float) {
        getSharedPreferences(context).edit().putFloat(KEY_SD_CFG, cfg.coerceIn(1f, 20f)).apply()
    }

    fun getCurrentPresetId(context: Context): String {
        return getSharedPreferences(context).getString(KEY_CURRENT_PRESET_ID, "") ?: ""
    }

    fun setCurrentPresetId(context: Context, presetId: String) {
        getSharedPreferences(context).edit().putString(KEY_CURRENT_PRESET_ID, presetId).apply()
    }

    fun getBraveSearchApiKey(context: Context): String {
        return getSharedPreferences(context).getString(KEY_BRAVE_SEARCH_API_KEY, "") ?: ""
    }

    fun setBraveSearchApiKey(context: Context, apiKey: String) {
        getSharedPreferences(context).edit().putString(KEY_BRAVE_SEARCH_API_KEY, apiKey.trim()).apply()
    }

    fun isEnableThinking(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_ENABLE_THINKING, false)
    }

    fun setEnableThinking(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_ENABLE_THINKING, enabled).apply()
    }

    fun isRequireMultimodal(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_REQUIRE_MULTIMODAL, false)
    }

    fun setRequireMultimodal(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_REQUIRE_MULTIMODAL, enabled).apply()
    }

    fun isSecretModeEnabled(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_SECRET_MODE_ENABLED, false)
    }

    fun setSecretModeEnabled(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_SECRET_MODE_ENABLED, enabled).apply()
    }

    fun setSecretModePin(context: Context, pin: String) {
        val hash = hashPin(pin)
        getSharedPreferences(context).edit().putString(KEY_SECRET_MODE_PIN_HASH, hash).apply()
    }

    fun verifySecretModePin(context: Context, pin: String): Boolean {
        val prefs = getSharedPreferences(context)
        val storedHash = prefs.getString(KEY_SECRET_MODE_PIN_HASH, null) ?: return false
        return storedHash == hashPin(pin)
    }

    fun clearSecretModePin(context: Context) {
        getSharedPreferences(context).edit()
            .remove(KEY_SECRET_MODE_PIN_HASH)
            .remove(KEY_SECRET_MODE_ENABLED)
            .apply()
    }

    fun hasSecretModePin(context: Context): Boolean {
        return getSharedPreferences(context).contains(KEY_SECRET_MODE_PIN_HASH)
    }

    fun isAlwaysLockEnabled(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_ALWAYS_LOCK_ENABLED, false)
    }

    fun setAlwaysLockEnabled(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_ALWAYS_LOCK_ENABLED, enabled).apply()
    }

    fun isStopKeyboardLearningEnabled(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_STOP_KEYBOARD_LEARNING, false)
    }

    fun setStopKeyboardLearningEnabled(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_STOP_KEYBOARD_LEARNING, enabled).apply()
    }

    fun isSdUseOpenCL(context: Context): Boolean {
        // NOTE: 既定値は false。CPU 版 MNN モデル (`*_cpu`) を使うユーザーが
        // 暗黙で UNET だけ OpenCL に逃がされ、モバイル GPU 上でカーネル JIT +
        // 重み転送が発生し初回 1 ステップに数十秒かかる事故を防ぐ。
        // 明示的に QNN/GPU を選択したユーザーだけ true にする運用に統一する。
        return getSharedPreferences(context).getBoolean(KEY_SD_USE_OPENCL, false)
    }

    fun setSdUseOpenCL(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_SD_USE_OPENCL, enabled).apply()
    }
}

