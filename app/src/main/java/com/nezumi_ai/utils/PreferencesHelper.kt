package com.nezumi_ai.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.sd.SdScheduler
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
    private const val KEY_SD_SCHEDULER = "sd_scheduler"
    private const val KEY_SD_PROMPT = "sd_prompt"
    private const val KEY_SD_NEGATIVE_PROMPT = "sd_negative_prompt"
    private const val KEY_CURRENT_PRESET_ID = "current_preset_id"
    private const val KEY_BRAVE_SEARCH_API_KEY = "brave_search_api_key"
    private const val KEY_ENABLE_THINKING = "enable_thinking"
    private const val KEY_THINKING_EFFORT = "thinking_effort"
    private const val KEY_REQUIRE_MULTIMODAL = "require_multimodal"
    private const val KEY_LLAMACPP_IMAGE_MAX_TOKENS = "llamacpp_image_max_tokens"
    private const val KEY_SECRET_MODE_PIN_HASH = "secret_mode_pin_hash"
    private const val KEY_SECRET_MODE_ENABLED = "secret_mode_enabled"
    private const val KEY_ALWAYS_LOCK_ENABLED = "always_lock_enabled"
    private const val KEY_STOP_KEYBOARD_LEARNING = "stop_keyboard_learning"
    private const val KEY_SD_USE_OPENCL = "sd_use_opencl"
    private const val KEY_MINIAPP_DEV_MODE = "miniapp_dev_mode"
 // 新設: 全般タブで切り替えられる UI 表示オプション。既定はいずれも「表示しない」。
    private const val KEY_SHOW_CONTEXT_METER = "show_context_meter"
    private const val KEY_SHOW_TPS = "show_tps"
    private const val KEY_SHOW_TTFT = "show_ttft"
 // スクリーンショット無効化 (FLAG_SECURE を常時有効化するかどうか)
    private const val KEY_DISABLE_SCREENSHOT = "disable_screenshot"
 // アプリ UI の言語 (i18n)。SYSTEM / JA / EN のいずれか。
    private const val KEY_LANGUAGE = "app_language"

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_LIGHT = "LIGHT"
    const val THEME_DARK = "DARK"

    // i18n: 設定画面の「全般」から切り替えられる UI 言語。
    //  - LANG_SYSTEM: 端末デフォルトのロケールに追従
    //  - LANG_JA / LANG_EN: 明示的に日本語 / 英語に固定
    const val LANG_SYSTEM = "SYSTEM"
    const val LANG_JA = "JA"
    const val LANG_EN = "EN"

    // シンキングの推論エフォート (添付シートの Low / Medium / High セグメント)。
    //   値はチャットテンプレ系エンジンの `reasoning_effort` に合わせて小文字で保持する。
    const val THINKING_EFFORT_LOW = "low"
    const val THINKING_EFFORT_MEDIUM = "medium"
    const val THINKING_EFFORT_HIGH = "high"
    // 初回起動時の既定値 (モック仕様: Thinking ON / effort Low)。
    const val DEFAULT_THINKING_EFFORT = THINKING_EFFORT_LOW
    // 従来互換の全レベル (テンプレート非対応モデルへのフォールバック用)。
    val ALL_THINKING_EFFORTS = listOf(THINKING_EFFORT_LOW, THINKING_EFFORT_MEDIUM, THINKING_EFFORT_HIGH)

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

    /**
     * アプリ UI の言語 (SYSTEM / JA / EN) を取得する。
     * 設定 > 全般 > 言語 で切り替え可能。実際の適用は
     * [LocaleHelper.wrap] を Activity / Application の `attachBaseContext` で
     * 呼び出すことで行う。
     */
    fun getLanguage(context: Context): String {
        val prefs = getSharedPreferences(context)
        return prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, lang: String) {
        val normalized = when (lang.uppercase()) {
            LANG_JA -> LANG_JA
            LANG_EN -> LANG_EN
            else -> LANG_SYSTEM
        }
        val prefs = getSharedPreferences(context)
        prefs.edit().putString(KEY_LANGUAGE, normalized).apply()
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
        val raw = getSharedPreferences(context).getString(KEY_SD_BACKEND, "mnn") ?: "mnn"
        return when (raw.lowercase()) {
            "opencl", "gpu" -> "opencl"
            "mnn", "cpu" -> "mnn"
            // 旧 QNN / auto 設定は MNN CPU に移行
            "qnn", "npu", "gpu_npu", "auto" -> "mnn"
            else -> "mnn"
        }
    }

    fun setSdBackend(context: Context, backend: String) {
        val normalized = when (backend.lowercase()) {
            "opencl", "gpu" -> "opencl"
            else -> "mnn"
        }
        getSharedPreferences(context).edit().putString(KEY_SD_BACKEND, normalized).apply()
        // OpenCL 選択時のみ GPU パスを有効化
        setSdUseOpenCL(context, normalized == "opencl")
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

    fun getSdScheduler(context: Context): String {
        val raw = getSharedPreferences(context).getString(KEY_SD_SCHEDULER, SdScheduler.DEFAULT.id)
        return SdScheduler.fromId(raw).id
    }

    fun setSdScheduler(context: Context, scheduler: String) {
        val normalized = SdScheduler.fromId(scheduler).id
        getSharedPreferences(context).edit().putString(KEY_SD_SCHEDULER, normalized).apply()
    }

    // 画像生成画面を閉じてもプロンプトを復元できるよう、入力値を保持する。
    //   steps / cfg / scheduler と同じく「変更の都度保存・初期値は保存値」方式。
    fun getSdPrompt(context: Context): String {
        return getSharedPreferences(context).getString(KEY_SD_PROMPT, "") ?: ""
    }

    fun setSdPrompt(context: Context, prompt: String) {
        getSharedPreferences(context).edit().putString(KEY_SD_PROMPT, prompt).apply()
    }

    fun getSdNegativePrompt(context: Context): String {
        return getSharedPreferences(context).getString(KEY_SD_NEGATIVE_PROMPT, "") ?: ""
    }

    fun setSdNegativePrompt(context: Context, prompt: String) {
        getSharedPreferences(context).edit().putString(KEY_SD_NEGATIVE_PROMPT, prompt).apply()
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

    fun getThinkingEffort(context: Context): String {
        return getSharedPreferences(context).getString(KEY_THINKING_EFFORT, DEFAULT_THINKING_EFFORT)
            ?: DEFAULT_THINKING_EFFORT
    }

    fun setThinkingEffort(context: Context, effort: String) {
        getSharedPreferences(context).edit().putString(KEY_THINKING_EFFORT, effort).apply()
    }

    /**
     * 保存済み / 選択中のエフォート値を、モデルが解釈可能なレベル集合 [supportedLevels]
     * に収まるよう正規化する (後方互換ガード)。
     * 範囲外の値 (例: BINARY モデルに対する "medium") は先頭レベルにフォールバックし、
     * [supportedLevels] が空なら既定値を返す。非対応モデル切り替え時のクラッシュや
     * 不正なプロンプト生成を防ぐ。
     */
    fun normalizeThinkingEffortForLevels(effort: String, supportedLevels: List<String>): String {
        if (supportedLevels.isEmpty()) return DEFAULT_THINKING_EFFORT
        val normalized = effort.trim().lowercase()
        return if (normalized in supportedLevels) normalized else supportedLevels.first()
    }

    /**
     * ロード対象モデルの chat_template が解釈できる思考強度レベル一覧を返す。
     *
     * ユーザーが明示テンプレート (カスタム / ビルトイン) を選んでいればそれを、
     * 未選択 (auto) なら GGUF メタデータの `tokenizer.chat_template` を解析する
     * (解析自体は [ModelNameHeuristics.parseReasoningEffortGranularity])。
     *
     * 戻り値の意味:
     *  - 空リスト: テンプレートが effort を解釈しない (UI は選択肢を出さない)
     *  - 1 要素  : BINARY (例: Granite 4.x の "low" のみ)
     *  - 複数    : GRADED (検出レベル集合。3 値とは限らない)
     * GGUF 以外のモデルやテンプレート読み取り失敗時は従来互換の 3 値
     * [ALL_THINKING_EFFORTS] にフォールバックする。
     */
    fun resolveSupportedThinkingEffortLevels(context: Context, modelPathOrName: String): List<String> {
        if (modelPathOrName.isBlank()) return ALL_THINKING_EFFORTS
        val isLocalGguf = modelPathOrName.endsWith(".gguf", ignoreCase = true) &&
            java.io.File(modelPathOrName).isAbsolute
        if (!isLocalGguf) return ALL_THINKING_EFFORTS
        return runCatching {
            val template = com.nezumi_ai.data.inference.PromptTemplateStore
                .resolveTemplate(context, modelPathOrName)
                ?: GgufMetadataReader.readChatTemplate(java.io.File(modelPathOrName))
                ?: return ALL_THINKING_EFFORTS
            ModelNameHeuristics.supportedEffortLevels(
                ModelNameHeuristics.parseReasoningEffortGranularity(template)
            )
        }.getOrDefault(ALL_THINKING_EFFORTS)
    }

    fun isRequireMultimodal(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_REQUIRE_MULTIMODAL, false)
    }

    fun setRequireMultimodal(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_REQUIRE_MULTIMODAL, enabled).apply()
    }

    /**
     * llama.cpp (mtmd) の 1 画像あたり最大トークン数 (--image-max-tokens 相当)。
     * 動的解像度ビジョンモデルでのみ使われる。0 = デフォルト (256 トークン)。
     */
    fun getLlamaCppImageMaxTokens(context: Context): Int {
        return getSharedPreferences(context).getInt(KEY_LLAMACPP_IMAGE_MAX_TOKENS, 0)
    }

    fun setLlamaCppImageMaxTokens(context: Context, tokens: Int) {
        getSharedPreferences(context).edit()
            .putInt(KEY_LLAMACPP_IMAGE_MAX_TOKENS, tokens.coerceIn(0, 8192)).apply()
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

 // コンテキストメーターの表示可否。既定は「表示しない (false)」。
    fun isShowContextMeter(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_SHOW_CONTEXT_METER, false)
    }

    fun setShowContextMeter(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_SHOW_CONTEXT_METER, enabled).apply()
    }

    // Mini App Platform (仕様 v1.1 §32 Developer Mode):
    // 未署名/未信頼鍵の Mini App インストールを許可するか。既定は無効 (false)。
    // 同意自体はインストールのたびに明示提示される (§35.5.4) ので、ここはあくまで機能の有効化のみ。
    fun isMiniAppDevModeEnabled(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_MINIAPP_DEV_MODE, false)
    }

    fun setMiniAppDevModeEnabled(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_MINIAPP_DEV_MODE, enabled).apply()
    }

 // トークン/秒表示の可否。既定は「表示しない (false)」。
    fun isShowTps(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_SHOW_TPS, false)
    }

    fun setShowTps(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_SHOW_TPS, enabled).apply()
    }

 // TTFT (最初のトークンまでの時間) 表示の可否。既定は「表示しない (false)」。
    fun isShowTtft(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_SHOW_TTFT, false)
    }

    fun setShowTtft(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_SHOW_TTFT, enabled).apply()
    }

 // スクリーンショット無効化。既定は無効 (false)。
    fun isDisableScreenshot(context: Context): Boolean {
        return getSharedPreferences(context).getBoolean(KEY_DISABLE_SCREENSHOT, false)
    }

    fun setDisableScreenshot(context: Context, enabled: Boolean) {
        getSharedPreferences(context).edit().putBoolean(KEY_DISABLE_SCREENSHOT, enabled).apply()
    }
}

