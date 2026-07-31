package com.nezumi_ai.voicevox

import android.content.Context
import android.util.Log
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime as BlockingOnnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk as BlockingOpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer as BlockingSynthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile as BlockingVoiceModelFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream

class VoicevoxManager(private val context: Context) {

    companion object {
        private const val TAG = "VoicevoxManager"
        const val VVM_BASE_URL = "https://raw.githubusercontent.com/VOICEVOX/voicevox_vvm/main/vvms"
        private const val DICT_URL = "https://downloads.sourceforge.net/open-jtalk/open_jtalk_dic_utf_8-1.11.tar.gz"
        private const val DICT_DIR_NAME = "open_jtalk_dic_utf_8-1.11"
        private const val DEFAULT_MODEL_FILE_NAME = "3.vvm"
        const val DEFAULT_STYLE_ID = 9
        private const val PREFS_NAME = "voicevox_settings"
        private const val KEY_STYLE_ID = "selected_style_id"
        private const val KEY_MODEL_FILE_NAME = "selected_model_file_name"

        val modelCatalog: List<VoiceModelCatalogEntry> = buildVoiceModelCatalog()
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
        val displayName: String = "$fileName / ${styles.distinctBy { it.speakerName }.joinToString("・") { it.speakerName }}"
        val shortDescription: String = styles.joinToString("、") { "${it.speakerName}/${it.styleName}(${it.styleId})" }
    }

    data class VoiceStyle(
        val speakerName: String,
        val styleName: String,
        val styleId: Int
    ) {
        val displayName: String = "$speakerName / $styleName ($styleId)"
    }

    private var onnxruntime: BlockingOnnxruntime? = null
    private var openJtalk: BlockingOpenJtalk? = null
    private var synthesizer: BlockingSynthesizer? = null
    private var voiceModelFile: BlockingVoiceModelFile? = null
    private var selectedStyleId = DEFAULT_STYLE_ID

    @Volatile
    private var isInitialized = false

    private val initializeMutex = Mutex()

    private val modelFile: File by lazy {
        File(context.filesDir, "voicevox_model.vvm")
    }

    private val dictDir: File by lazy {
        File(context.filesDir, DICT_DIR_NAME)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSelectedModelFileName(): String {
        return prefs.getString(KEY_MODEL_FILE_NAME, DEFAULT_MODEL_FILE_NAME) ?: DEFAULT_MODEL_FILE_NAME
    }

    suspend fun downloadSelectedModel(entry: VoiceModelCatalogEntry): Boolean = withContext(Dispatchers.IO) {
        try {
            releaseInternal()
            val tmpFile = File(context.filesDir, "${entry.fileName}.download")
            if (tmpFile.exists()) tmpFile.delete()
            downloadFile(entry.url, tmpFile)
            if (modelFile.exists()) modelFile.delete()
            check(tmpFile.renameTo(modelFile)) { "モデルファイルを置き換えられませんでした" }
            prefs.edit()
                .putString(KEY_MODEL_FILE_NAME, entry.fileName)
                .putInt(KEY_STYLE_ID, defaultStyleIdFor(entry))
                .apply()
            selectedStyleId = getSavedStyleId()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download VOICEVOX model: ${entry.fileName}", e)
            false
        }
    }

    suspend fun initialize(): Boolean {
        if (isInitialized) return true

        return initializeMutex.withLock {
            // ロック取得後に再チェック（二重初期化防止）
            if (isInitialized) return@withLock true

            withContext(Dispatchers.IO) {
                try {
                    if (!modelFile.exists()) {
                        val entry = modelCatalog.firstOrNull { it.fileName == getSelectedModelFileName() }
                            ?: modelCatalog.first { it.fileName == DEFAULT_MODEL_FILE_NAME }
                        Log.d(TAG, "Downloading VOICEVOX model: ${entry.fileName}")
                        downloadFile(entry.url, modelFile)
                    }

                    if (!isValidDictionaryDir(dictDir)) {
                        Log.d(TAG, "Downloading OpenJTalk dictionary...")
                        val dictArchive = File(context.filesDir, "$DICT_DIR_NAME.tar.gz")
                        dictDir.deleteRecursively()
                        downloadFile(DICT_URL, dictArchive)
                        extractTarGz(dictArchive, context.filesDir)
                        dictArchive.delete()
                    }

                    Log.d(TAG, "Initializing VOICEVOX...")
                    onnxruntime = BlockingOnnxruntime.loadOnce().perform()
                    openJtalk = BlockingOpenJtalk(dictDir.absolutePath)
                    synthesizer = BlockingSynthesizer.builder(onnxruntime!!, openJtalk!!).build()
                    voiceModelFile = BlockingVoiceModelFile(modelFile.absolutePath)
                    Log.d(TAG, "VoiceModelFile id: ${voiceModelFile!!.id}")
                    synthesizer!!.loadVoiceModel(voiceModelFile!!)
                    Log.d(TAG, "loadVoiceModel done")
                    selectedStyleId = resolveStyleId(voiceModelFile!!)

                    isInitialized = true
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

    fun setSelectedStyleId(styleId: Int) {
        prefs.edit().putInt(KEY_STYLE_ID, styleId).apply()
        selectedStyleId = styleId
        Log.d(TAG, "Selected VOICEVOX style ID: $styleId")
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
        isInitialized = false
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

    private fun downloadFile(url: String, outputFile: File) {
        URL(url).openStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
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
        entry("6.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("No.7", "ノーマル", 29), style("No.7", "アナウンス", 30), style("No.7", "読み聞かせ", 31)
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
        entry("20.vvm", VoicevoxManager.VoiceModelCategory.TALK, listOf(
            style("ユーレイちゃん", "ノーマル", 102), style("ユーレイちゃん", "甘々", 103),
            style("ユーレイちゃん", "哀しみ", 104), style("ユーレイちゃん", "ささやき", 105),
            style("ユーレイちゃん", "ツクモちゃん", 106)
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
            style("後鬼", "人間ver.", 3027), style("No.7", "ノーマル", 3029),
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
