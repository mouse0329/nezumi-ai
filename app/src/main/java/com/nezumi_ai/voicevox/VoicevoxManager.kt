package com.nezumi_ai.voicevox

import android.content.Context
import android.util.Log
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime as BlockingOnnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk as BlockingOpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer as BlockingSynthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile as BlockingVoiceModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * VOICEVOX の音声合成マネージャ。
 *
 * 新しい仕様:
 * - 話者 (styleId) の選択だけを UI から受け付ける
 * - 選択された話者を含む .vvm がローカルに無ければ自動でダウンロードして自動初期化する
 * - 既にダウンロード済みならそのまま自動初期化する
 * - 外部ファイル (.vvm) の手動追加はサポートしない (importFromUri のような API は無い)
 * - 手動でモデルファイルを選ぶ UI も削除。カタログ側から styleId で解決する
 * - モデルを削除する API (deleteInstalledModel) は残す
 */
class VoicevoxManager(private val context: Context) {

    companion object {
        private const val TAG = "VoicevoxManager"
        const val VVM_BASE_URL = "https://raw.githubusercontent.com/VOICEVOX/voicevox_vvm/main/vvms"
        private const val DICT_URL = "https://downloads.sourceforge.net/open-jtalk/open_jtalk_dic_utf_8-1.11.tar.gz"
        private const val DICT_DIR_NAME = "open_jtalk_dic_utf_8-1.11"
        // 既定は「ずんだもん / ノーマル」(styleId=3)。これは 0.vvm に含まれる。
        const val DEFAULT_MODEL_FILE_NAME = "0.vvm"
        const val DEFAULT_STYLE_ID = 3
        private const val PREFS_NAME = "voicevox_settings"
        private const val KEY_STYLE_ID = "selected_style_id"
        private const val KEY_MODEL_FILE_NAME = "selected_model_file_name"

        val modelCatalog: List<VoiceModelCatalogEntry> = buildVoiceModelCatalog()

        /** 全ての .vvm を平坦化した話者一覧。UI の「誰の声にするか」ドロップダウンで使う。 */
        val allStyles: List<VoiceStyle> by lazy {
            modelCatalog
                .flatMap { entry -> entry.styles.map { it to entry } }
                .sortedWith(
                    compareBy<Pair<VoiceStyle, VoiceModelCatalogEntry>> { it.second.category.ordinal }
                        .thenBy { it.first.speakerName }
                        .thenBy { it.first.styleId }
                )
                .map { it.first }
        }

        /** 指定 styleId を含む .vvm カタログエントリを返す。 */
        fun catalogEntryForStyle(styleId: Int): VoiceModelCatalogEntry? {
            return modelCatalog.firstOrNull { entry ->
                entry.styles.any { it.styleId == styleId }
            }
        }

        /** ファイル名で .vvm カタログエントリを返す (companion からもアクセスできるように公開)。 */
        fun catalogEntryFor(fileName: String): VoiceModelCatalogEntry? =
            modelCatalog.firstOrNull { it.fileName == fileName }
    }

    enum class VoiceModelCategory(val label: String) {
        TALK("トーク"),
        SONG("ソング"),
        NEMO("Nemo トーク")
    }

    data class VoiceModelCatalogEntry(
        val fileName: String,
        val category: VoiceModelCategory,
        val styles: List<VoiceStyle>
    ) {
        val url: String = "${VoicevoxManager.VVM_BASE_URL}/$fileName"
        val speakerNames: List<String> = styles.map { it.speakerName }.distinct()
        val displayName: String = "$fileName / ${speakerNames.joinToString("・")}"
        val shortDescription: String = styles.joinToString("、") { "${it.speakerName}/${it.styleName}(${it.styleId})" }

        /** この .vvm に含まれる話者のクレジット表記一覧。 */
        val credits: List<String> = VoicevoxLicense.creditsFor(speakerNames)

        /** この .vvm に含まれる話者のライセンス項目一覧。 */
        val licenses: List<VoicevoxLicense.Entry> = VoicevoxLicense.entriesFor(speakerNames)

        /** UI に 1 行で出すクレジット文字列。 */
        val creditLine: String = credits.joinToString(" / ")
    }

    data class VoiceStyle(
        val speakerName: String,
        val styleName: String,
        val styleId: Int
    ) {
        val displayName: String = "$speakerName / $styleName"
        val detailName: String = "$speakerName / $styleName ($styleId)"

        /** 生成音声に付与すべきクレジット表記。 */
        val credit: String get() = VoicevoxLicense.creditFor(speakerName)

        val license: VoicevoxLicense.Entry? get() = VoicevoxLicense.forSpeaker(speakerName)
    }

    private var onnxruntime: BlockingOnnxruntime? = null
    private var openJtalk: BlockingOpenJtalk? = null
    private var synthesizer: BlockingSynthesizer? = null
    private var voiceModelFile: BlockingVoiceModelFile? = null
    private var selectedStyleId = DEFAULT_STYLE_ID

    @Volatile
    private var isInitialized = false

    private val initializeMutex = Mutex()

    /** 現在ロードされている .vvm ファイル名。UI 監視用。 */
    private val _installedModelFileName = MutableStateFlow<String?>(null)
    val installedModelFileName: StateFlow<String?> = _installedModelFileName.asStateFlow()

    /** 現在の選択済み styleId。UI が話者切替を即時反映するために監視する。 */
    private val _selectedStyleIdFlow = MutableStateFlow(DEFAULT_STYLE_ID)
    val selectedStyleIdFlow: StateFlow<Int> = _selectedStyleIdFlow.asStateFlow()

    /** ロード完了状態。UI がスピナー制御に使う。 */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val modelFile: File by lazy {
        File(context.filesDir, "voicevox_model.vvm")
    }

    private val dictDir: File by lazy {
        File(context.filesDir, DICT_DIR_NAME)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        selectedStyleId = getSavedStyleId()
        _selectedStyleIdFlow.value = selectedStyleId
        if (modelFile.isFile) {
            _installedModelFileName.value = getSelectedModelFileName()
        }
    }

    fun getSelectedModelFileName(): String {
        return prefs.getString(KEY_MODEL_FILE_NAME, DEFAULT_MODEL_FILE_NAME) ?: DEFAULT_MODEL_FILE_NAME
    }

    /** 現在選択中の音声モデルの保存先。 */
    fun modelFilePath(): File = modelFile

    /** OpenJTalk 辞書ディレクトリ。 */
    fun dictionaryDir(): File = dictDir

    fun isDictionaryReady(): Boolean = isValidDictionaryDir(dictDir)

    fun isModelFileReady(): Boolean = modelFile.isFile && modelFile.length() > 0L



    /**
     * ダウンロード済みの一時ファイルを正式な音声モデルとして採用する。
     * ModelDownloadWorker から呼ばれる。
     */
    fun installDownloadedModel(entry: VoiceModelCatalogEntry, downloadedFile: File): Boolean {
        return try {
            releaseInternal()
            require(downloadedFile.isFile && downloadedFile.length() > 0L) {
                "ダウンロードファイルが不正です: ${downloadedFile.absolutePath}"
            }
            if (modelFile.exists()) modelFile.delete()
            val moved = downloadedFile.renameTo(modelFile)
            if (!moved) {
                downloadedFile.copyTo(modelFile, overwrite = true)
                downloadedFile.delete()
            }
            // styleId を保持しつつモデル名を更新
            val savedStyleId = getSavedStyleId()
            val styleToUse = if (entry.styles.any { it.styleId == savedStyleId }) {
                savedStyleId
            } else {
                defaultStyleIdFor(entry)
            }
            prefs.edit()
                .putString(KEY_MODEL_FILE_NAME, entry.fileName)
                .putInt(KEY_STYLE_ID, styleToUse)
                .apply()
            selectedStyleId = styleToUse
            _selectedStyleIdFlow.value = selectedStyleId
            _installedModelFileName.value = entry.fileName
            Log.i(TAG, "Installed VOICEVOX model ${entry.fileName} (styleId=$selectedStyleId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install VOICEVOX model: ${entry.fileName}", e)
            false
        }
    }

    /**
     * OpenJTalk 辞書を必要なら取得する。進捗コールバック付き。
     * ModelDownloadWorker のフェーズ 2 から呼ばれる。
     */
    fun ensureDictionary(onProgress: ((downloaded: Long, total: Long) -> Unit)? = null): Boolean {
        if (isValidDictionaryDir(dictDir)) return true
        return try {
            val dictArchive = File(context.filesDir, "$DICT_DIR_NAME.tar.gz")
            dictDir.deleteRecursively()
            if (dictArchive.exists()) dictArchive.delete()
            downloadFile(DICT_URL, dictArchive, onProgress)
            extractTarGz(dictArchive, context.filesDir)
            dictArchive.delete()
            isValidDictionaryDir(dictDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare OpenJTalk dictionary", e)
            false
        }
    }

    /** 現在選択中の話者に対応するクレジット表記。UI やライセンス画面で使う。 */
    fun currentCredit(): String {
        val styleId = getSavedStyleId()
        val speaker = modelCatalog
            .flatMap { it.styles }
            .firstOrNull { it.styleId == styleId }
            ?.speakerName
        return if (speaker != null) VoicevoxLicense.creditFor(speaker) else VoicevoxLicense.VOICEVOX_CREDIT
    }

    /**
     * VOICEVOX を初期化する。
     *
     * この関数は「既にファイルが揃っている場合のみ」初期化する。
     * .vvm や OpenJTalk 辞書が未ダウンロードの場合は何もせず false を返す。
     * ダウンロードは必ず [ModelDownloadWorker.enqueueVoicevoxModel] 経由に一本化している（競合回避）。
     * Worker 完了後にブロードキャスト経由で initialize() が呼ばれ直す。
     */
    suspend fun initialize(): Boolean {
        if (isInitialized) return true

        return initializeMutex.withLock {
            // ロック取得後に再チェック（二重初期化防止）
            if (isInitialized) return@withLock true

            withContext(Dispatchers.IO) {
                // ファイルが揃っていないときは何もしない。
                // 以前はここで同期ダウンロードを走らせていたが、Worker 側の
                // installDownloadedModel() と並行して releaseInternal() と open が交错し、
                //   "Null pointer in rust value from Java" (VoiceModelFile.rsDrop)
                // を起こしていた。DL は必ず Worker に任せる。
                if (!modelFile.isFile || modelFile.length() == 0L) {
                    Log.d(TAG, "initialize(): .vvm not present yet; skipping (will re-init after download)")
                    return@withContext false
                }
                if (!isValidDictionaryDir(dictDir)) {
                    Log.d(TAG, "initialize(): OpenJTalk dictionary not ready; skipping (will re-init after download)")
                    return@withContext false
                }

                try {
                    Log.d(TAG, "Initializing VOICEVOX...")
                    onnxruntime = BlockingOnnxruntime.loadOnce().perform()
                    openJtalk = BlockingOpenJtalk(dictDir.absolutePath)
                    synthesizer = BlockingSynthesizer.builder(onnxruntime!!, openJtalk!!).build()
                    voiceModelFile = BlockingVoiceModelFile(modelFile.absolutePath)
                    Log.d(TAG, "VoiceModelFile id: ${voiceModelFile!!.id}")
                    synthesizer!!.loadVoiceModel(voiceModelFile!!)
                    Log.d(TAG, "loadVoiceModel done")
                    selectedStyleId = resolveStyleId(voiceModelFile!!)
                    _selectedStyleIdFlow.value = selectedStyleId
                    _installedModelFileName.value = getSelectedModelFileName()

                    isInitialized = true
                    _isReady.value = true
                    Log.d(TAG, "VOICEVOX initialized successfully")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize VOICEVOX", e)
                    releaseInternal()
                    false
                }
            }
        }
    }

    suspend fun synthesize(text: String): ByteArray? = withContext(Dispatchers.IO) {
        // synthesizeはmutexを取らずisInitializedだけ見る
        if (!isInitialized) {
            Log.e(TAG, "Synthesizer not initialized")
            return@withContext null
        }

        val currentSynthesizer = synthesizer
        if (currentSynthesizer == null) {
            Log.e(TAG, "Synthesizer is null")
            return@withContext null
        }

        try {
            val audioQuery = currentSynthesizer.createAudioQuery(text, selectedStyleId)
            currentSynthesizer.synthesis(audioQuery, selectedStyleId).perform()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to synthesize text", e)
            null
        }
    }

    fun release() {
        releaseInternal()
    }

    /**
     * 選択中の .vvm を削除する。次回選択時に再ダウンロードが走る。
     * 呼び出し側は必要に応じて事前に [release] を呼ぶこと。
     */
    fun deleteInstalledModel(): Boolean {
        releaseInternal()
        val ok = if (modelFile.exists()) modelFile.delete() else true
        if (ok) _installedModelFileName.value = null
        return ok
    }

    suspend fun getAvailableStyles(): List<VoiceStyle> = withContext(Dispatchers.IO) {
        if (!modelFile.isFile) return@withContext emptyList()
        var file: BlockingVoiceModelFile? = null
        try {
            file = BlockingVoiceModelFile(modelFile.absolutePath)
            readStyles(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read VOICEVOX styles", e)
            emptyList()
        } finally {
            runCatching { file?.close() }
        }
    }

    fun getSavedStyleId(): Int {
        return prefs.getInt(KEY_STYLE_ID, DEFAULT_STYLE_ID)
    }

    /**
     * 話者 (styleId) を選択する。
     * - 選択した styleId が現在ロード中の .vvm に含まれる場合は即時反映
     * - 別の .vvm に属する場合は KEY_MODEL_FILE_NAME を更新し、[isReady] を false に落とす。
     *   ダウンロード & 初期化の実行は呼び出し側 (ModelDownloadWorker + MyApplication) に委ねる。
     */
    fun setSelectedStyleId(styleId: Int) {
        val editor = prefs.edit().putInt(KEY_STYLE_ID, styleId)
        val hostEntry = catalogEntryForStyle(styleId)
        val currentModelFileName = getSelectedModelFileName()
        val needsSwitch = hostEntry != null && hostEntry.fileName != currentModelFileName
        if (hostEntry != null) {
            editor.putString(KEY_MODEL_FILE_NAME, hostEntry.fileName)
        }
        editor.apply()
        selectedStyleId = styleId
        _selectedStyleIdFlow.value = styleId
        if (needsSwitch) {
            // 別モデルへの切替。以降の synthesize は再初期化まで無効。
            releaseInternal()
        }
        Log.d(TAG, "Selected VOICEVOX style ID: $styleId (hostModel=${hostEntry?.fileName}, needsSwitch=$needsSwitch)")
    }

    /**
     * 現在の設定に必要な .vvm がローカルに用意されているか。
     * true なら (init を呼ぶだけで) 追加ダウンロードなしで初期化できる。
     */
    fun isCurrentSelectionReady(): Boolean {
        val fileName = getSelectedModelFileName()
        val expected = catalogEntryFor(fileName) ?: return false
        // インストール済みモデル名も揃っている必要がある
        return modelFile.isFile &&
            _installedModelFileName.value == expected.fileName &&
            isValidDictionaryDir(dictDir)
    }

    private fun releaseInternal() {
        try {
            voiceModelFile?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing voiceModelFile", e)
        }
        voiceModelFile = null
        synthesizer = null
        openJtalk = null
        onnxruntime = null
        selectedStyleId = getSavedStyleId()
        _selectedStyleIdFlow.value = selectedStyleId
        isInitialized = false
        _isReady.value = false
    }

    private fun resolveStyleId(modelFile: BlockingVoiceModelFile): Int {
        return try {
            val metas = modelFile.metas
            val styles = readStyles(modelFile)

            Log.d(TAG, "Loaded speakers: ${metas.size}")
            metas.forEach { speaker ->
                Log.d(TAG, "Speaker: ${speaker.name}, UUID: ${speaker.speakerUuid}, version: ${speaker.version}")
                speaker.styles.forEach { style ->
                    Log.d(TAG, "  Style: ${style.name}, ID: ${style.id}, type: ${style.type}")
                }
            }

            val savedStyleId = getSavedStyleId()
            if (styles.any { it.styleId == savedStyleId }) {
                Log.d(TAG, "Using saved VOICEVOX style ID: $savedStyleId")
                savedStyleId
            } else {
                val fallbackId = styles.firstOrNull { it.styleId == DEFAULT_STYLE_ID }?.styleId
                    ?: styles.firstOrNull()?.styleId
                    ?: DEFAULT_STYLE_ID
                Log.w(TAG, "Saved VOICEVOX style ID $savedStyleId was not found. Using fallback style ID: $fallbackId")
                fallbackId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get speaker meta", e)
            DEFAULT_STYLE_ID
        }
    }

    private fun readStyles(modelFile: BlockingVoiceModelFile): List<VoiceStyle> {
        return modelFile.metas.flatMap { speaker ->
            speaker.styles.map { style ->
                VoiceStyle(
                    speakerName = speaker.name,
                    styleName = style.name,
                    styleId = style.id
                )
            }
        }.sortedWith(compareBy<VoiceStyle> { it.speakerName }.thenBy { it.styleId })
    }

    private fun defaultStyleIdFor(entry: VoiceModelCatalogEntry): Int {
        return entry.styles.firstOrNull { it.styleId == DEFAULT_STYLE_ID }?.styleId
            ?: entry.styles.firstOrNull()?.styleId
            ?: DEFAULT_STYLE_ID
    }

    private fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) {
        val connection = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    var lastNotifyMs = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (onProgress != null && now - lastNotifyMs >= 300L) {
                            lastNotifyMs = now
                            onProgress(downloaded, total)
                        }
                    }
                    onProgress?.invoke(downloaded, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidDictionaryDir(dir: File): Boolean {
        return dir.isDirectory &&
            File(dir, "sys.dic").isFile &&
            File(dir, "unk.dic").isFile &&
            File(dir, "matrix.bin").isFile
    }

    private fun extractTarGz(tarGzFile: File, outputDir: File) {
        val canonicalOutputDir = outputDir.canonicalFile
        GZIPInputStream(tarGzFile.inputStream()).use { input ->
            val header = ByteArray(512)
            while (readFully(input, header) == header.size) {
                if (header.all { it == 0.toByte() }) break

                val name = readTarString(header, 0, 100)
                val prefix = readTarString(header, 345, 155)
                val entryName = if (prefix.isBlank()) name else "$prefix/$name"
                val size = readTarOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()
                val outFile = File(outputDir, entryName).canonicalFile

                require(outFile.path.startsWith(canonicalOutputDir.path + File.separator)) {
                    "Unsafe tar entry path: $entryName"
                }

                if (typeFlag == '5') {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        copyExactly(input, output, size)
                    }
                }

                val padding = (512 - (size % 512)) % 512
                skipExactly(input, padding)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { buffer[it] == 0.toByte() } ?: offset + length
        return String(buffer, offset, end - offset, Charsets.US_ASCII).trim()
    }

    private fun readTarOctal(buffer: ByteArray, offset: Int, length: Int): Long {
        return readTarString(buffer, offset, length).trim().ifBlank { "0" }.toLong(8)
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) throw java.io.EOFException("Unexpected end of tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipExactly(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() == -1) {
                throw java.io.EOFException("Unexpected end of tar padding")
            } else {
                remaining--
            }
        }
    }
}

/**
 * 同梱する .vvm カタログ。
 *
 * 収録方針: 「クレジット表記のみで商用・非商用ともに利用可能」な音声ライブラリのみを載せる。
 * 商用利用が認められていない No.7 / ユーレイちゃん（6.vvm・20.vvm、および s0.vvm の No.7 スタイル）は
 * 意図的に除外している。詳細は [VoicevoxLicense] と docs/VOICEVOX_TERMS.md を参照。
 */
private fun buildVoiceModelCatalog(): List<VoicevoxManager.VoiceModelCatalogEntry> {
    fun style(speaker: String, name: String, id: Int) = VoicevoxManager.VoiceStyle(speaker, name, id)
    fun entry(
        fileName: String,
        category: VoicevoxManager.VoiceModelCategory,
        styles: List<VoicevoxManager.VoiceStyle>
    ) = VoicevoxManager.VoiceModelCatalogEntry(
        fileName = fileName,
        category = category,
        styles = styles
    )

    return listOf(
        entry("0.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("四国めたん", "ノーマル", 2), style("四国めたん", "あまあま", 0),
            style("四国めたん", "ツンツン", 6), style("四国めたん", "セクシー", 4),
            style("ずんだもん", "ノーマル", 3), style("ずんだもん", "あまあま", 1),
            style("ずんだもん", "ツンツン", 7), style("ずんだもん", "セクシー", 5),
            style("春日部つむぎ", "ノーマル", 8), style("雨晴はう", "ノーマル", 10)
        )),
        entry("1.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(style("冥鳴ひまり", "ノーマル", 14))),
        entry("2.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("九州そら", "ノーマル", 16), style("九州そら", "あまあま", 15),
            style("九州そら", "ツンツン", 18), style("九州そら", "セクシー", 17)
        )),
        entry("3.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("波音リツ", "ノーマル", 9), style("波音リツ", "クイーン", 65),
            style("中国うさぎ", "ノーマル", 61), style("中国うさぎ", "おどろき", 62),
            style("中国うさぎ", "こわがり", 63), style("中国うさぎ", "へろへろ", 64)
        )),
        entry("4.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("玄野武宏", "ノーマル", 11), style("剣崎雌雄", "ノーマル", 21)
        )),
        entry("5.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("四国めたん", "ささやき", 36), style("四国めたん", "ヒソヒソ", 37),
            style("ずんだもん", "ささやき", 22), style("ずんだもん", "ヒソヒソ", 38),
            style("九州そら", "ささやき", 19)
        )),
        entry("7.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("後鬼", "人間ver.", 27), style("後鬼", "ぬいぐるみver.", 28)
        )),
        entry("8.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("WhiteCUL", "ノーマル", 23), style("WhiteCUL", "たのしい", 24),
            style("WhiteCUL", "かなしい", 25), style("WhiteCUL", "びえーん", 26)
        )),
        entry("9.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("白上虎太郎", "ふつう", 12), style("白上虎太郎", "わーい", 32),
            style("白上虎太郎", "びくびく", 33), style("白上虎太郎", "おこ", 34),
            style("白上虎太郎", "びえーん", 35)
        )),
        entry("10.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("玄野武宏", "喜び", 39), style("玄野武宏", "ツンギレ", 40),
            style("玄野武宏", "悲しみ", 41), style("ちび式じい", "ノーマル", 42)
        )),
        entry("11.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("櫻歌ミコ", "ノーマル", 43), style("櫻歌ミコ", "第二形態", 44),
            style("櫻歌ミコ", "ロリ", 45), style("ナースロボ＿タイプＴ", "ノーマル", 47),
            style("ナースロボ＿タイプＴ", "楽々", 48), style("ナースロボ＿タイプＴ", "恐怖", 49),
            style("ナースロボ＿タイプＴ", "内緒話", 50)
        )),
        entry("12.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("†聖騎士 紅桜†", "ノーマル", 51), style("雀松朱司", "ノーマル", 52),
            style("麒ヶ島宗麟", "ノーマル", 53)
        )),
        entry("13.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("春歌ナナ", "ノーマル", 54), style("猫使アル", "ノーマル", 55),
            style("猫使アル", "おちつき", 56), style("猫使アル", "うきうき", 57),
            style("猫使ビィ", "ノーマル", 58), style("猫使ビィ", "おちつき", 59),
            style("猫使ビィ", "人見知り", 60)
        )),
        entry("14.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("栗田まろん", "ノーマル", 67), style("あいえるたん", "ノーマル", 68),
            style("満別花丸", "ノーマル", 69), style("満別花丸", "元気", 70),
            style("満別花丸", "ささやき", 71), style("満別花丸", "ぶりっ子", 72),
            style("満別花丸", "ボーイ", 73), style("琴詠ニア", "ノーマル", 74)
        )),
        entry("15.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("ずんだもん", "ヘロヘロ", 75), style("ずんだもん", "なみだめ", 76),
            style("青山龍星", "ノーマル", 13), style("青山龍星", "熱血", 81),
            style("青山龍星", "不機嫌", 82), style("青山龍星", "喜び", 83),
            style("青山龍星", "しっとり", 84), style("青山龍星", "かなしみ", 85),
            style("青山龍星", "囁き", 86), style("もち子さん", "ノーマル", 20),
            style("もち子さん", "セクシー／あん子", 66), style("もち子さん", "泣き", 77),
            style("もち子さん", "怒り", 78), style("もち子さん", "喜び", 79),
            style("もち子さん", "のんびり", 80), style("小夜/SAYO", "ノーマル", 46)
        )),
        entry("16.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("後鬼", "人間（怒り）ver.", 87), style("後鬼", "鬼ver.", 88)
        )),
        entry("17.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(style("Voidoll", "ノーマル", 89))),
        entry("18.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("ぞん子", "ノーマル", 90), style("ぞん子", "低血圧", 91),
            style("ぞん子", "覚醒", 92), style("ぞん子", "実況風", 93),
            style("中部つるぎ", "ノーマル", 94), style("中部つるぎ", "怒り", 95),
            style("中部つるぎ", "ヒソヒソ", 96), style("中部つるぎ", "おどおど", 97),
            style("中部つるぎ", "絶望と敗北", 98)
        )),
        entry("19.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("離途", "ノーマル", 99), style("離途", "シリアス", 101), style("黒沢冴白", "ノーマル", 100)
        )),
        entry("21.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("猫使アル", "つよつよ", 110), style("猫使アル", "へろへろ", 111),
            style("猫使ビィ", "つよつよ", 112), style("東北ずん子", "ノーマル", 107),
            style("東北きりたん", "ノーマル", 108), style("東北イタコ", "ノーマル", 109)
        )),
        entry("22.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("あんこもん", "ノーマル", 113), style("あんこもん", "つよつよ", 114),
            style("あんこもん", "よわよわ", 115), style("あんこもん", "けだるげ", 116)
        )),
        entry("23.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(style("あんこもん", "ささやき", 117))),
        entry("24.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("夜語トバリ", "ノーマル", 118), style("夜語トバリ", "明るい", 119),
            style("夜語トバリ", "哀しみ", 120), style("夜語トバリ", "呆れ", 121),
            style("暁記ミタマ", "ノーマル", 122), style("暁記ミタマ", "怒り", 123),
            style("暁記ミタマ", "哀しみ", 124), style("暁記ミタマ", "ささやき", 125),
            style("里石ユカ", "つぼみ", 126)
        )),
        entry("s0.vvm", VoicevoxManager.VoiceModelCategory.SONG, listOf(
            style("四国めたん", "ノーマル", 3002), style("四国めたん", "あまあま", 3000),
            style("四国めたん", "ツンツン", 3006), style("四国めたん", "セクシー", 3004),
            style("四国めたん", "ヒソヒソ", 3037), style("ずんだもん", "ノーマル", 3003),
            style("ずんだもん", "あまあま", 3001), style("ずんだもん", "ツンツン", 3007),
            style("ずんだもん", "セクシー", 3005), style("ずんだもん", "ヒソヒソ", 3038),
            style("ずんだもん", "ヘロヘロ", 3075), style("ずんだもん", "なみだめ", 3076),
            style("春日部つむぎ", "ノーマル", 3008), style("雨晴はう", "ノーマル", 3010),
            style("波音リツ", "ノーマル", 3009), style("波音リツ", "クイーン", 3065),
            style("波音リツ", "ノーマル", 6000), style("玄野武宏", "ノーマル", 3011),
            style("青山龍星", "ノーマル", 3013), style("冥鳴ひまり", "ノーマル", 3014),
            style("九州そら", "ノーマル", 3016), style("もち子さん", "ノーマル", 3020),
            style("剣崎雌雄", "ノーマル", 3021), style("WhiteCUL", "ノーマル", 3023),
            style("後鬼", "人間ver.", 3027),
            style("ちび式じい", "ノーマル", 3042), style("櫻歌ミコ", "ノーマル", 3043),
            style("小夜/SAYO", "ノーマル", 3046), style("ナースロボ＿タイプＴ", "ノーマル", 3047),
            style("†聖騎士 紅桜†", "ノーマル", 3051), style("雀松朱司", "ノーマル", 3052),
            style("麒ヶ島宗麟", "ノーマル", 3053), style("春歌ナナ", "ノーマル", 3054),
            style("猫使アル", "ノーマル", 3055), style("猫使ビィ", "ノーマル", 3058),
            style("中国うさぎ", "ノーマル", 3061), style("栗田まろん", "ノーマル", 3067),
            style("あいえるたん", "ノーマル", 3068), style("満別花丸", "ノーマル", 3069),
            style("琴詠ニア", "ノーマル", 3074)
        )),
        entry("n0.vvm", VoicevoxManager.VoiceModelCategory.NEMO, listOf(
            style("女声1", "ノーマル", 10005), style("女声2", "ノーマル", 10007),
            style("女声3", "ノーマル", 10004), style("女声4", "ノーマル", 10003),
            style("女声5", "ノーマル", 10008), style("女声6", "ノーマル", 10006),
            style("男声1", "ノーマル", 10001), style("男声2", "ノーマル", 10000),
            style("男声3", "ノーマル", 10002)
        ))
    )
}
